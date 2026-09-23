package com.laodao.signage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启。
 *
 * 可靠性从高到低，三种做法（README 里有详细步骤）：
 *
 *  1. 把本应用设为「桌面 / 主屏幕」——开机后系统自己就把它拉起来，最稳。
 *     在 AndroidManifest.xml 里取消 HOME intent-filter 那段注释即可。
 *
 *  2. 在盒子的系统设置里，把本应用加进「自启动 / 后台运行白名单」。
 *     国内品牌盒子基本都要手动加这一步，不然广播收不到或被拦。
 *
 *  3. 只靠下面这个广播 —— Android 10 以后系统会拦截「后台启动界面」，
 *     原生系统上可能被静默丢弃。所以它只能当补充，不能当唯一手段。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(Config.TAG, "收到开机广播：$action")

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        runCatching { context.startActivity(launch) }
            .onFailure {
                Log.w(
                    Config.TAG,
                    "开机直接拉起界面被系统拦截。请把本应用设为桌面，或加入自启动白名单。",
                    it
                )
            }
    }
}
