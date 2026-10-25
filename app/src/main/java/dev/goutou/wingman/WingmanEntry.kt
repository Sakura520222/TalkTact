package dev.goutou.wingman

import android.app.Activity
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.MODULE_PKG
import dev.goutou.wingman.config.NAMESPACE
import dev.goutou.wingman.wechat.ChatHost
import dev.goutou.wingman.wechat.PanelRegistry
import dev.goutou.wingman.wechat.TextCapture
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口（libxposed Modern API 102）。
 *
 * 和 legacy 入口（IXposedHookLoadPackage.handleLoadPackage）的对应关系：
 *
 * | legacy                                   | 现在                                  |
 * |------------------------------------------|---------------------------------------|
 * | `handleLoadPackage(lpparam)`             | `onPackageReady(param)`               |
 * | `lpparam.packageName`                    | `param.packageName`                   |
 * | `lpparam.classLoader`                    | `param.classLoader`                   |
 * | `lpparam.processName`                    | `onModuleLoaded` 的 `param.processName` |
 * | `XC_MethodHook` 的 before/after           | 拦截器链 `intercept { chain -> ... }`  |
 *
 * 认聊天页的策略**没有变**：不靠类名猜 LauncherUI/ChattingUI（微信一改版本就失效），
 * 而是挂在每个 Activity 的 onResume/onPause 上，由面板自己用
 * 「有没有输入框 + 有没有消息列表」的结构特征判断。
 *
 * 类名被 `META-INF/xposed/java_init.list` 按字符串引用，改名要两边一起改。
 */
class WingmanEntry : XposedModule() {

    /**
     * onPackageReady 的 param 里没有进程名，只有包名，所以进程名得从 onModuleLoaded 存下来。
     * 微信和 QQ 都有 :push 之类的子进程，模块只在各自的主进程里干活。
     */
    private var processName = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XposedApi.bind(this)
        processName = param.processName
        XposedApi.log("已加载：进程=$processName system_server=${param.isSystemServer}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 自己的 App 进程：保留「模块已激活」探针。
        if (param.packageName == MODULE_PKG) {
            hookSelf(param.classLoader)
            return
        }
        val host = ChatHost.fromPackage(param.packageName) ?: return
        if (ChatHost.matchesMainProcess(param.packageName, processName)) hookHost(param.classLoader, host)
    }

    /**
     * 探针。
     *
     * App 首页据此显示「微信内已生效」——是真检测，而不是原版那种「请你自己点一下确认」。
     * 类名/包名按字符串引用（模块 App 自己的进程里，它的类由应用 ClassLoader 加载，
     * 所以要用 param.classLoader 去 load，不能直接用 ::class.java）。
     */
    private fun hookSelf(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("$NAMESPACE.ModuleStatus")
            val method = cls.getDeclaredMethod("isActive")
            // 完全不调 chain.proceed()：等价于 legacy 的 XC_MethodReplacement.returnConstant(true)
            hook(method).setId(HOOK_SELF).intercept { true }
            XposedApi.log("已 hook 自身探针 $NAMESPACE.ModuleStatus.isActive")
        } catch (t: Throwable) {
            XposedApi.log("hook self failed: $t")
        }
    }

    private fun hookHost(classLoader: ClassLoader, host: ChatHost) {
        XposedApi.log("hooked ${host.packageName}")
        // QQ 默认关闭，不能在显式启用 / 手动读取之前预挂正文捕获钩子。
        if (host == ChatHost.WECHAT) preHookLearned(classLoader)

        try {
            // onResume：先让宿主跑完，再拉起面板（等价于 legacy 的 afterHookedMethod）。
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            hook(onResume).setId(HOOK_RESUME).intercept { chain ->
                val result = chain.proceed()
                try {
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == host.packageName) {
                        PanelRegistry.onResume(activity)
                    }
                } catch (t: Throwable) {
                    XposedApi.log("onResume: $t")
                }
                result
            }

            // onPause：先收面板，再让宿主继续（等价于 legacy 的 beforeHookedMethod）。
            val onPause = Activity::class.java.getDeclaredMethod("onPause")
            hook(onPause).setId(HOOK_PAUSE).intercept { chain ->
                try {
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == host.packageName) {
                        PanelRegistry.onPause(activity)
                    }
                } catch (t: Throwable) {
                    XposedApi.log("onPause: $t")
                }
                chain.proceed()
            }

            // 移除后台轮询、背景节点和视图；清理失败也必须继续宿主销毁流程。
            val onDestroy = Activity::class.java.getDeclaredMethod("onDestroy")
            hook(onDestroy).setId(HOOK_DESTROY).intercept { chain ->
                try {
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == host.packageName) {
                        PanelRegistry.onDestroy(activity)
                    }
                } catch (t: Throwable) {
                    XposedApi.log("onDestroy: $t")
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            XposedApi.log("hook ${host.label} failed: $t")
        }
    }

    /**
     * 上次运行学到过「自己画字」的控件类，这里在聊天页渲染之前先把钩子挂好，
     * 这样第二次打开聊天页不用等重新绑定就能读到正文。
     */
    private fun preHookLearned(classLoader: ClassLoader) {
        try {
            val prefs = XposedApi.prefs() ?: return
            val names = prefs.getStringSet(Keys.LEARNED, emptySet()).orEmpty()
            var hooked = 0
            for (name in names) {
                // 两个宿主共用配置，但不能把 QQ 后续学到的正文类预挂进微信进程。
                if (name.startsWith("com.tencent.mobileqq.") || name.startsWith("com.tencent.qqnt.") ||
                    name.startsWith("com.tencent.aio.")) continue
                if (TextCapture.hookByName(classLoader, name, remember = false)) hooked++
            }
            if (hooked > 0) XposedApi.log("已预先挂好 $hooked 个控件类")
        } catch (t: Throwable) {
            XposedApi.log("preHook 失败: $t")
        }
    }

    private companion object {
        const val HOOK_SELF = "wingman.module.status"
        const val HOOK_RESUME = "wingman.activity.onResume"
        const val HOOK_PAUSE = "wingman.activity.onPause"
        const val HOOK_DESTROY = "wingman.activity.onDestroy"
    }
}
