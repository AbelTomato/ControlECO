package com.controleco.app.capture

import android.content.Context

/** 监听范围里的应用分组（设置页「监听的 App」按此分组展示） */
internal enum class AppGroup(val title: String) {
    PAY("支付"),
    BANK("银行"),
    SHOP("电商 / 外卖"),
}

/** 目录里的一个应用：包名、显示名、分组、默认是否监听 */
internal data class CatalogApp(
    val packageName: String,
    val label: String,
    val group: AppGroup,
    val defaultOn: Boolean = true,
)

/**
 * 监听范围（用户可控）：哪些应用的通知/页面会被采集。
 *
 * 为什么需要：支付三包之外的通道越加越多（银行推送、电商收银台），全量监听既吵又费电，
 * 用户需要能按应用开关（见 docs/自动记账捕获机制.md 第一节）。
 *
 * 语义：目录内应用的开关决定"是否采集"；目录**外**的应用由各自通道的闸门决定
 * （短信靠号码+签名、银行推送靠正文签名）——目录只覆盖"包名可枚举"的通知/页面通道。
 * 存储：SharedPreferences 字符串集合，缺省 = 目录默认值（首读写回，之后以用户选择为准）。
 */
internal object CaptureAppCatalog {

    private const val PREFS_NAME = "settings"
    private const val KEY_WATCHED = "watched_packages"

    /** 目录（顺序即设置页展示顺序） */
    val apps: List<CatalogApp> = listOf(
        // 支付
        CatalogApp(NotificationRules.WECHAT, "微信", AppGroup.PAY),
        CatalogApp(NotificationRules.ALIPAY, "支付宝", AppGroup.PAY),
        CatalogApp(NotificationRules.UNIONPAY, "云闪付", AppGroup.PAY),
        // 银行（动账推送；短信通道由「银行动账短信」开关单独控制）
        CatalogApp("com.icbc", "工商银行", AppGroup.BANK),
        CatalogApp("com.chinamworld.main", "建设银行", AppGroup.BANK),
        CatalogApp("com.android.bankabc", "农业银行", AppGroup.BANK),
        CatalogApp("com.chinamworld.bocmbci", "中国银行", AppGroup.BANK),
        CatalogApp("cmb.pb", "招商银行", AppGroup.BANK),
        CatalogApp("com.bankcomm.Bankcomm", "交通银行", AppGroup.BANK),
        CatalogApp("com.yitong.mbank.psbc", "邮储银行", AppGroup.BANK),
        CatalogApp("com.ecitic.bank.mobile", "中信银行", AppGroup.BANK),
        CatalogApp("com.cebbank.mobile.cemb", "光大银行", AppGroup.BANK),
        CatalogApp("com.cmbc.cc.mbank", "民生银行", AppGroup.BANK),
        CatalogApp("com.spdbccc.app", "浦发银行", AppGroup.BANK),
        CatalogApp("com.pingan.pabank.activity", "平安银行", AppGroup.BANK),
        CatalogApp("com.cgbchina.xpt", "广发银行", AppGroup.BANK),
        CatalogApp("com.cib.bank", "兴业银行", AppGroup.BANK),
        CatalogApp("com.huaxia.mobilebank", "华夏银行", AppGroup.BANK),
        // 电商/外卖
        CatalogApp("com.jingdong.app.mall", "京东", AppGroup.SHOP),
        CatalogApp("com.jd.jr.jdpay", "京东支付", AppGroup.SHOP),
        CatalogApp("com.jd.jrapp", "京东金融", AppGroup.SHOP),
        CatalogApp("com.taobao.taobao", "淘宝", AppGroup.SHOP),
        CatalogApp("com.tmall.wireless", "天猫", AppGroup.SHOP),
        CatalogApp("com.xunmeng.pinduoduo", "拼多多", AppGroup.SHOP),
        CatalogApp("com.ss.android.ugc.aweme", "抖音", AppGroup.SHOP),
        CatalogApp("com.ss.android.ugc.live", "抖音（直播）", AppGroup.SHOP),
        CatalogApp("com.sankuai.meituan", "美团", AppGroup.SHOP),
        CatalogApp("com.sankuai.meituan.takeaway", "美团外卖", AppGroup.SHOP),
        CatalogApp("me.ele", "饿了么", AppGroup.SHOP),
    )

    /** 目录内包名的默认监听集（默认全开：宁多勿漏，用户可逐项关掉） */
    fun defaults(): Set<String> = apps.filter { it.defaultOn }.map { it.packageName }.toSet()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前监听集合（缺省 = 目录默认值；首次读取时写回，之后以用户选择为准） */
    fun watched(context: Context): Set<String> {
        val p = prefs(context)
        val stored = p.getStringSet(KEY_WATCHED, null)
        if (stored != null) return stored
        val initial = defaults()
        p.edit().putStringSet(KEY_WATCHED, initial).apply()
        return initial
    }

    /** 是否监听某应用（**目录外的包名返回 false**：目录只覆盖包名可枚举的通知/页面通道） */
    fun isWatched(context: Context, packageName: String): Boolean = packageName in watched(context)

    /** 目录外来源（短信/未知银行包名）不因目录开关被拒，交给各通道自己的签名闸门 */
    fun isWatchedOrUnknown(context: Context, packageName: String): Boolean =
        watchedOrUnknown(watched(context), packageName)

    /** 纯判定（便于单测）：目录外一律放行；目录内看用户开关 */
    internal fun watchedOrUnknown(watchedSet: Set<String>, packageName: String): Boolean =
        apps.none { it.packageName == packageName } || packageName in watchedSet

    fun setWatched(context: Context, packageName: String, watched: Boolean) {
        val current = watched(context).toMutableSet()
        if (watched) current.add(packageName) else current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_WATCHED, current).apply()
    }

    /** 整组开关（全开/全关） */
    fun setGroupWatched(context: Context, group: AppGroup, watched: Boolean) {
        val current = watched(context).toMutableSet()
        val groupPackages = apps.filter { it.group == group }.map { it.packageName }
        if (watched) current.addAll(groupPackages) else current.removeAll(groupPackages)
        prefs(context).edit().putStringSet(KEY_WATCHED, current).apply()
    }
}
