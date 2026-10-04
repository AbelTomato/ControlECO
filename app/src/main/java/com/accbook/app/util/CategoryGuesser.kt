package com.accbook.app.util

/**
 * 账单导入时的分类猜测：关键词 → 分类名（仅返回名称，由调用方映射到对应类型的分类 id）。
 * 规则随使用经验持续补充；命中顺序即列表顺序。
 */
object CategoryGuesser {

    private val rules: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf("餐饮", "外卖", "美团", "饿了么", "肯德基", "麦当劳", "星巴克", "奶茶", "咖啡", "小吃", "快餐", "餐", "食堂", "食品"),
        "交通" to listOf("交通", "打车", "滴滴", "地铁", "公交", "出行", "加油", "高铁", "火车", "机票", "12306", "单车", "货拉拉", "停车"),
        "购物" to listOf("淘宝", "京东", "拼多多", "天猫", "购物", "商城", "抖音商城", "唯品会"),
        "日用" to listOf("日用", "超市", "便利店", "百货"),
        "居住" to listOf("房租", "水电", "电费", "燃气", "物业", "话费", "宽带", "充值中心", "供暖"),
        "娱乐" to listOf("娱乐", "游戏", "电影", "视频", "音乐", "会员", "票务", "演出", "KTV"),
        "医疗" to listOf("医疗", "药房", "药店", "医院", "挂号", "诊所", "体检"),
        "工资" to listOf("工资", "薪酬", "薪", "奖金"),
        "理财" to listOf("理财", "基金", "收益", "利息", "余额宝", "零钱通", "赎回", "分红"),
        "红包" to listOf("红包", "转账", "收款码", "赞赏"),
    )

    fun guess(text: String): String {
        val hit = rules.firstOrNull { (_, keywords) -> keywords.any { text.contains(it) } }
        return hit?.first ?: "其他"
    }
}
