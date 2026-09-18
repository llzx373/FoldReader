package com.llzx373.foldreader.core.format.clean

/**
 * 噪音过滤（问题 8、9、10 与「内容噪音层」补充项）。
 *
 * 两条通道：
 * - **整行**：[isNoiseLine] 命中即删整行（广告、站点推广、防盗声明、字数统计、作者的话、
 *   论坛残留、装饰线）。
 * - **行内**：[exciseInline] 只切除句中夹带的 URL/域名与「（本章完）」类尾标——问题 9 最常见
 *   的形态其实是「广告与正文粘连在同一行」，整行删除会连带吞掉正文。
 *
 * 误伤是本功能最大的风险，所以规则分两级：
 * - `FULL`：整行必须完全匹配才删（最安全，用于有明确锚点/固定长度的规则）。
 * - `SHORT`：整行只出现在「短行」里才算（推广短语往往就是独立一小句，而正文里出现同名词
 *   概率低但存在），[Rule.maxLen] 为 0 表示不限长度。
 *
 * 用户自定义正则（`adCleanRules`）沿用旧语义：只要**行内出现**就删整行。
 */
internal object NoiseRules {

    private enum class Mode { FULL, SHORT }

    private data class Rule(
        val name: String,
        val regex: Regex,
        val mode: Mode = Mode.FULL,
        val maxLen: Int = 0,
        /**
         * 这条规则只解决「整行就是一个网址/域名」。开启行内切除时应当让 [exciseInline] 去做，
         * 否则「正文 + 句尾网址」会被整行删掉，正文跟着一起没了（问题 9 的行内变体）。
         */
        val inlineHandled: Boolean = false,
    )

    private val DOMAIN = Regex(
        "(?:[a-z0-9][a-z0-9\\-]{0,60}\\.)+(?:com|net|org|cc|cn|me|top|xyz|info|vip|la|club|site|online|tv|co|shop|art|fun|live|pro|us|biz|app|dev|io)\\b(?:/\\S*)?",
        RegexOption.IGNORE_CASE,
    )

    private val RULES: List<Rule> = listOf(
        // ── 网址 / 域名 ─────────────────────────────────────────────
        Rule("url", Regex("^\\s*(?:https?://|www\\.)\\S*\\s*$")),
        Rule("url", Regex("(?:https?://|www\\.)\\S+"), Mode.SHORT, maxLen = 80, inlineHandled = true),
        Rule("domain", DOMAIN, Mode.SHORT, maxLen = 80, inlineHandled = true),

        // ── 站点推广 / 防盗声明 ──────────────────────────────────────
        Rule("site", Regex("(?:请记住|敬请记住|记住|收藏)(?:本|以下|这个)?(?:站|书|网址|域名|地址)"), Mode.SHORT, maxLen = 80),
        Rule("site", Regex("(?:最新|更新)(?:章节|网址|域名|地址)"), Mode.SHORT, maxLen = 80),
        Rule("site", Regex("(?:全文阅读|无弹窗|全文字|免费阅读|手机阅读|手机用户请|电脑用户请|书友群|最新章节请)"), Mode.SHORT, maxLen = 80),
        Rule("site", Regex("(?:本书|本文|本作)(?:由|来自|首发|整理|转载|采集|独家)"), Mode.SHORT, maxLen = 80),
        Rule("site", Regex("(?:更多|精彩|后续)(?:章节|内容)(?:请|尽在|访问|登录|关注|搜索)"), Mode.SHORT, maxLen = 80),
        Rule("vote-request", Regex("(?:如果|若)(?:您|你)?(?:喜欢|觉得).{0,8}(?:请|求).{0,6}(?:收藏|推荐|分享|订阅)"), Mode.SHORT, maxLen = 80),
        Rule("anti-piracy", Regex("(?:本章|全文|后续|内容).{0,4}(?:未完|未结束|待续)"), Mode.SHORT, maxLen = 80),
        Rule("anti-piracy", Regex("(?:点击|请|烦请).{0,4}(?:下一页|下一章|继续阅读|继续观看)"), Mode.SHORT, maxLen = 80),
        Rule("anti-piracy", Regex("^\\s*(?:未完待续|本章未完|本章完|全文完|本章结束|未完|待续)\\s*[。.!！]?\\s*$")),
        Rule("disclaimer", Regex("(?:免责声明|版权所有|版权归|侵权|请勿用于|仅供(?:学习|交流|参考)|删除处理)"), Mode.SHORT, maxLen = 100),
        Rule("encoding-notice", Regex("(?:本文件|本书|本文)(?:由|为).{0,10}(?:整理|制作|扫描|校对|提供)"), Mode.SHORT, maxLen = 100),

        // ── 字数统计 ────────────────────────────────────────────────
        Rule("word-count", Regex("^\\s*(?:本章|本页|全文)?\\s*(?:共|计|约)?\\s*[0-9０-９,，]{2,9}\\s*(?:字|字数|字符)\\s*$")),
        Rule("word-count", Regex("^\\s*(?:本章|本页|全文).{0,6}(?:共|计|约)\\s*[0-9０-９,，]{1,9}\\s*(?:字|字数|字符).*$")),

        // ── 作者的话 / 求票 ─────────────────────────────────────────
        Rule(
            "author-note",
            Regex("^\\s*[【\\[（(]?\\s*(?:作者的话|作者|公告|通知|说明|ps|p\\.s\\.|ps\\.)\\s*[】\\]）)]?\\s*[:：].*$", RegexOption.IGNORE_CASE),
        ),
        Rule("vote-request", Regex("(?:求|投|砸|给|需要).{0,6}(?:推荐票|月票|打赏)"), Mode.SHORT, maxLen = 60),
        Rule("vote-request", Regex("(?:推荐票|月票|打赏).{0,8}(?:谢谢|感谢|拜托|跪求|求各位)"), Mode.SHORT, maxLen = 60),
        Rule("vote-request", Regex("(?:求|谢谢|感谢|拜托).{0,6}(?:收藏|订阅|点赞|好评|推荐)"), Mode.SHORT, maxLen = 60),

        // ── 论坛残留（问题 10）─────────────────────────────────────
        Rule("forum-floor", Regex("^\\s*(?:第\\s*)?[0-9]{1,6}\\s*楼\\s*[:：]?.*$")),
        Rule("forum-floor", Regex("^\\s*[#＃]\\s*[0-9]{1,6}\\s*[:：].*$")),
        Rule("forum-quote", Regex("^\\s*(?:引用|回复|举报|沙发|板凳|地板|顶帖|点赞)\\s*[:：].*$")),
        Rule("forum-meta", Regex("^\\s*(?:UID|等级|积分|威望|金币|经验|在线时间|注册时间|发帖数?|用户组)\\s*[:：].*$", RegexOption.IGNORE_CASE)),
        Rule("forum-meta", Regex("^\\s*(?:发表于|来自|发自|via)\\s+\\S{1,20}.*$", RegexOption.IGNORE_CASE)),
        Rule("forum-time", Regex("^\\s*[0-9]{4}[-/年][0-9]{1,2}[-/月][0-9]{1,2}日?\\s*[0-9]{1,2}:[0-9]{2}.*$")),
        Rule("forum-time", Regex("^\\s*[0-9]{1,2}:[0-9]{2}\\s*(?:AM|PM)?\\s*$", RegexOption.IGNORE_CASE)),
        Rule("forum-signature", Regex("^\\s*[-—]{2,}\\s*(?:来自|发自|以下为|签名)\\s*.*$")),
        Rule("forum-tag", Regex("^\\s*[#＃][^\\s#＃]{1,20}(?:\\s+[#＃][^\\s#＃]{1,20}){1,6}\\s*$")),

        // ── 装饰线 / 分隔线 ─────────────────────────────────────────
        Rule("decoration", Regex("^\\s*[\\-=*_~☆★※◇◆■□●○▽▼△▲#+·]{3,}\\s*$")),
        Rule(
            "decoration",
            Regex("^\\s*(?:[☆★※◇◆■□●○]\\s*){3,}$"),
        ),
    )

    private val INLINE_PATTERNS: List<Regex> = listOf(
        Regex("\\s*(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE),
        Regex(
            "\\s*(?:[a-z0-9][a-z0-9\\-]{0,60}\\.)+(?:com|net|org|cc|cn|me|top|xyz|info|vip|la|club|site|online|tv|co|shop|art|fun|live|pro|us|biz|app|dev|io)\\b(?:/\\S*)?",
            RegexOption.IGNORE_CASE,
        ),
        Regex("[（(【\\[]\\s*(?:本章完|未完待续|全文完|求票|求收藏|求推荐|求订阅|求打赏|谢谢支持|本章结束)\\s*[）)】\\]]"),
    )

    /** 切除后残留的重复标点（`……` `——` 是有意义的，故不在收敛集合里）。 */
    private val POST_REPEAT = Regex("([，、；：！？])\\1+")

    private val POST_SPACES = Regex(" {2,}")

    private val MASK_CHARS = charArrayOf('*', '＊', '●', '○', '■', '□', '◆', '◇')
    private val MASK_RUN = Regex("[*＊●○■□◆◇]{2,}")
    private const val MASK_REPLACEMENT = "＊＊"

    /**
     * 整行是否为噪音。返回命中的规则名（供报告样例）；不是噪音返回 null。
     *
     * @param inlineExcise 是否启用了行内切除。启用时跳过 [Rule.inlineHandled] 的规则，
     *   把「正文 + 句尾网址」交给 [exciseInline]，只删网址、保住正文。
     * @param builtInRules 是否启用内置规则库。用户自定义正则**不受它影响**——
     *   那是用户显式配置的，关掉 `filterNoise` 不等于把用户规则也一起关掉。
     */
    fun isNoiseLine(
        line: String,
        userPatterns: List<Regex> = emptyList(),
        inlineExcise: Boolean = false,
        builtInRules: Boolean = true,
    ): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (builtInRules) {
            for (rule in RULES) {
                if (inlineExcise && rule.inlineHandled) continue
                if (rule.mode == Mode.SHORT && rule.maxLen > 0 && trimmed.length > rule.maxLen) continue
                val hit = when (rule.mode) {
                    Mode.FULL -> rule.regex.matches(trimmed)
                    Mode.SHORT -> rule.regex.containsMatchIn(trimmed)
                }
                if (hit) return rule.name
            }
        }
        for (pattern in userPatterns) {
            if (runCatching { pattern.containsMatchIn(line) }.getOrDefault(false)) return "custom"
        }
        return null
    }

    /** 行内切除：删掉句中夹带的网址/域名与「（本章完）」类尾标，并收敛残留的重复标点与空格。 */
    fun exciseInline(line: String): String {
        if (line.isEmpty()) return line
        var out = line
        for (pattern in INLINE_PATTERNS) {
            if (pattern.containsMatchIn(out)) out = pattern.replace(out, "")
        }
        if (out == line) return line
        out = POST_SPACES.replace(out, " ")
        out = POST_REPEAT.replace(out, "$1")
        return out.trim()
    }

    /** 遮蔽/缺字符号规整：连续 2 个以上的遮蔽符号统一成一个 `＊＊`。 */
    fun normalizeMaskRuns(line: String): String {
        if (line.isEmpty()) return line
        if (line.none { it in MASK_CHARS }) return line
        return MASK_RUN.replace(line, MASK_REPLACEMENT)
    }
}
