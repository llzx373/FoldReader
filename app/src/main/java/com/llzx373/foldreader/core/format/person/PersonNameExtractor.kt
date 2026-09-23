package com.llzx373.foldreader.core.format.person

/**
 * 人名统计结果。[firstOffset] 为全书首次出场的字符偏移(逐章 feed 时已含 baseOffset 累积);
 * [count] 为出场次数(长名归并后的独立计数)。
 */
data class PersonMention(
    val name: String,
    val firstOffset: Long,
    val count: Int,
)

/**
 * 纯规则人名统计引擎:逐章喂入中文文本,启发式抽取人名并累计出场次数。纯 JVM,无 Android 依赖。
 *
 * 抽取策略(启发式,宁缺毋滥):
 * 1. 姓氏引导:内置高频姓氏表(复姓优先),「姓 + 1~3 字」为强候选,支持「老/小/阿」前缀(老魏、小王);
 *    名字部分遇到高频虚词字([BREAK_CHARS])、称谓词或非汉字即截断。
 *    截断后 2 字到最长的候选全部记录:「唐三睁」式误扩展由频次阈值兜底,前后缀重叠由长名归并处理。
 * 2. 称谓引导:「X + 先生/老师/警官/…」中称谓前的 1~3 字为名候选(称谓本身不入名)。
 *    单字候选只接受姓氏(王警官 -> 王);多字候选若以姓氏开头(苏沐橙小姐)说明姓氏路径已记录,跳过避免重复。
 * 3. 过滤:常见词黑名单([WORD_BLACKLIST],可持续补充)、「王爷/牛奶」类称谓固定词、含虚词字的候选。
 * 4. 归并:长名优先——短候选的某次出场若落在「已过阈值的长名」出场区间内,视为长名一部分不再独立计数
 *    (如「苏沐」恒为「苏沐橙」前缀,被归并;独立出现的「沐橙」不受影响)。
 * 5. 频次阈值:默认出场 ≥ [DEFAULT_MIN_COUNT] 次才收录,低于此多为偶发误识别。
 *
 * 已知权衡:「小舞」这类无姓氏名字、以截断字结尾的名字(如「浩然」的「然」)识别不到;
 * 规则引擎不求全,只求高准确率,黑名单可按语料持续补充。
 */
class PersonNameExtractor(
    private val minCount: Int = DEFAULT_MIN_COUNT,
) {
    /** 人名 -> 每次出场的绝对偏移(feed 顺序保证升序) */
    private val occurrences = LinkedHashMap<String, MutableList<Long>>()

    /** 喂入一章(或一段)文本,[baseOffset] 为该段在全书中的起始字符偏移。 */
    fun feed(text: String, baseOffset: Long) {
        var i = 0
        val n = text.length
        while (i < n) {
            // 复姓优先,再单姓
            val surnameLen = when {
                i + 1 < n && text.substring(i, i + 2) in COMPOUND_SURNAMES -> 2
                text[i] in SURNAMES -> 1
                else -> 0
            }
            if (surnameLen > 0) {
                // 「老/小/阿 + 姓」整体视作名字
                var start = i
                if (surnameLen == 1 && i > 0 && text[i - 1] in NAME_PREFIX_CHARS &&
                    (i < 2 || !isHan(text[i - 2]))
                ) {
                    start = i - 1
                }
                var end = i + surnameLen
                while (end < n && end - start < MAX_NAME_LEN && isHan(text[end]) &&
                    text[end] !in BREAK_CHARS && matchTitle(text, end) == 0
                ) {
                    end++
                }
                if (end - start >= MIN_NAME_LEN) {
                    if (start < i) record(text.substring(start, end), baseOffset + start)
                    for (len in MIN_NAME_LEN..(end - i)) {
                        record(text.substring(i, i + len), baseOffset + i)
                    }
                    i = end
                    continue
                }
            }
            // 称谓引导
            val titleLen = matchTitle(text, i)
            if (titleLen > 0) {
                recordTitleCandidate(text, i, titleLen, baseOffset)
                i += titleLen
            } else {
                i++
            }
        }
    }

    /** 汇总结果:长名归并 + 频次阈值过滤后,按出场次数降序(同次按首出场偏移升序)。 */
    fun result(): List<PersonMention> {
        // 已过阈值的名字及其独立出场区间(按长度降序处理,长名先定)
        val kept = ArrayList<Pair<String, List<Long>>>()
        for (name in occurrences.keys.sortedByDescending { it.length }) {
            // 只统计不落在任何「已过阈值长名」出场区间内的独立出场;
            // 未过阈值的长候选多为误扩展(欧阳修提/苏沐橙怒),无权覆盖短名
            val own = occurrences.getValue(name).filter { off ->
                kept.none { (longer, longerOffsets) ->
                    longer.contains(name) && longerOffsets.any { lo -> off >= lo && off < lo + longer.length }
                }
            }
            if (own.size >= minCount) kept += name to own
        }
        return kept
            .map { (name, own) -> PersonMention(name, own.first(), own.size) }
            .sortedWith(compareByDescending<PersonMention> { it.count }.thenBy { it.firstOffset })
    }

    private fun record(name: String, offset: Long) {
        if (name in WORD_BLACKLIST) return
        occurrences.getOrPut(name) { ArrayList() }.add(offset)
    }

    /** 称谓引导:回看称谓前的 1~3 个连续汉字作为名候选。 */
    private fun recordTitleCandidate(text: String, titleIndex: Int, titleLen: Int, baseOffset: Long) {
        var s = titleIndex
        while (s > 0 && titleIndex - s < MAX_NAME_LEN - 1 && isHan(text[s - 1])) s--
        val len = titleIndex - s
        if (len == 0) return
        val candidate = text.substring(s, titleIndex)
        // 单字候选只接受姓氏(王警官 -> 王),其余多为「我们老师」式误命中
        if (len == 1 && candidate[0] !in SURNAMES) return
        // 多字且以姓氏开头(苏沐橙小姐):姓氏路径已记录,跳过避免重复计数
        if (len >= 2 && candidate[0] in SURNAMES) return
        if (candidate.any { it in BREAK_CHARS }) return
        // 「王爷」「牛奶」这类固定词不算人名
        if (candidate + text.substring(titleIndex, titleIndex + titleLen) in TITLE_WORDS) return
        record(candidate, baseOffset + s)
    }

    /** 命中称谓词则返回其长度,否则 0。双字称谓优先于单字。 */
    private fun matchTitle(text: String, index: Int): Int {
        for (title in TITLES) {
            if (text.startsWith(title, index)) return title.length
        }
        return if (index < text.length && text[index] in TITLE_CHARS) 1 else 0
    }

    private fun isHan(c: Char): Boolean = c in '一'..'鿿'

    companion object {
        /** 默认频次阈值:出场 ≥3 次才收录。低于此多为「叶修文」「唐三睁」式偶发误识别。 */
        const val DEFAULT_MIN_COUNT = 3

        private const val MIN_NAME_LEN = 2
        private const val MAX_NAME_LEN = 4

        /** 高频单姓(百家姓常见 + 网文高频),约 150 个;宁可少收不可滥收。 */
        private val SURNAMES: Set<Char> = (
            "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾萧田董潘袁蔡蒋余杜" +
                "叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛闫段雷侯龙史陶" +
                "黎贺顾毛郝龚邵万钱严覃武戴莫孔汤常温康施牛樊葛洪庞伍翟凌毕聂丛岳齐祝柴安关苗迟" +
                "蓝路涂谷辛闵欧舒柯阮景柳穆冷盛翁纪靳卓官隋麦植楚燕颜喻尚司仇储云姬墨夜洛陌离尘炎" +
                "冰裴霍易骆甘宁战厉薄祁池乔管童庄华费廉岑"
            ).toSet()

        /** 常见复姓。 */
        private val COMPOUND_SURNAMES: Set<String> = setOf(
            "欧阳", "司马", "诸葛", "上官", "慕容", "令狐", "独孤", "长孙", "宇文", "皇甫",
            "公孙", "夏侯", "东方", "西门", "南宫", "百里", "尉迟", "拓跋", "端木", "呼延",
            "完颜", "耶律", "赫连", "澹台", "司空", "司徒",
        )

        /** 「老/小/阿 + 姓」前缀。 */
        private const val NAME_PREFIX_CHARS = "老小阿"

        /**
         * 名字部分的截断字:高频虚词/代词/动词/副词/形容词字。
         * 它们出现在名字后续位置的概率远高于出现在人名内部;代价是「浩然」「修文」式
         * 以这些字结尾的名字会被截断,属可接受损失。
         */
        private const val BREAK_CHARS =
            "的了是在我你他她它们有和与就不都一又上也很到说要去会着没看好这那里后前中间边面" +
                "头尾个么吗呢吧啊呀嘛之乎者矣焉哉得地过为于及把被让向从往哪什怎谁再才只却但而且若因所" +
                "问道想听闻见走跑出入进回坐站立笑哭喊叫吃喝打杀死活生开关买卖拿放找推拉抬飞跳爬点睁系" +
                "大小多少高低长短快慢强弱冷热新旧真假对错然时已曾将正便即竟忽突顿渐乃遂皆俱均全遍独唯" +
                "仅互相共齐"

        /** 双字称谓(匹配时优先于单字)。 */
        private val TITLES: List<String> = listOf(
            "先生", "女士", "小姐", "太太", "老师", "医生", "警官", "队长", "前辈", "同学",
        )

        /** 单字称谓。 */
        private const val TITLE_CHARS = "兄姐叔姨爷奶"

        /**
         * 常见词黑名单:候选串命中即丢弃。覆盖虚词、称谓及「姓+常用字」高频搭配
         * (王子/关系/安全/战斗…),可按语料持续补充。
         */
        private val WORD_BLACKLIST: Set<String> = setOf(
            "我们", "他们", "你们", "自己", "什么", "怎么", "这个", "那个", "哪个", "现在",
            "已经", "因为", "所以", "但是", "如果", "虽然", "就是", "不是", "没有", "还有",
            "大家", "老师", "老师傅", "师傅", "时候", "东西", "事情", "地方", "问题", "可以",
            "可能", "知道", "觉得", "这样", "那样", "一样", "还是", "只是", "只有", "然后",
            "终于", "突然", "忽然", "果然", "竟然", "当然", "反正", "而且", "不过", "可是",
            "于是", "关系", "王子", "王国", "王朝", "王后", "王室", "马路", "马虎", "文化",
            "文明", "文章", "文学", "南方", "北方", "东南", "西南", "东北", "西北", "风吹",
            "风声", "风沙", "花开", "花落", "花儿", "花朵", "君王", "墨迹", "墨水", "夜晚",
            "夜色", "尘土", "尘埃", "冰冷", "冰凉", "炎热", "牛奶", "奶茶", "云彩", "云朵",
            "雪花", "雪白", "欧洲", "厉害", "宁愿", "宁可", "安全", "安静", "安排", "康复",
            "温暖", "温度", "常常", "路口", "蓝色", "白色", "白天", "金色", "金钱", "甘心",
            "甘愿", "古老", "古代", "管理", "童年", "庄严", "庄稼", "华丽", "颜色", "司令",
            "司机", "仇恨", "顾客", "战斗", "战争", "战士", "战场",
        )

        /** 称谓引导的排除:「候选 + 称谓」为这些固定词时不算人名。 */
        private val TITLE_WORDS: Set<String> = setOf(
            "王爷", "少爷", "老爷", "大爷", "姑爷", "牛奶", "奶茶",
            "姐夫", "姐妹", "姨妈", "大叔", "大姨", "大姐", "奶奶", "爷爷", "叔叔",
        )
    }
}
