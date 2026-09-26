package com.llzx373.foldreader.core.ocr

/**
 * 模型清单（M21/M22，R7/R8）。
 *
 * 每条记录钉死 文件名 + 全量 SHA-256 + 字节数：SAF 导入时按文件名匹配条目、
 * 按哈希校验完整性（[com.llzx373.foldreader.core.ai.android.ModelManager]）。
 * 用户从官方地址/镜像自行下载，应用不联网下载（v2.6 网络政策）。
 *
 * 哈希来源（2026-09-26 实算并交叉验证）：
 * - det/rec 四个模型与 RapidOCR 官方 default_models.yaml（v3.9.2）公布的 SHA256 一致；
 * - 气泡模型与 HuggingFace API 返回的 LFS oid（即官方 SHA-256）一致。
 *
 * 哈希漂移处理（docs/AI功能需求与实施.md §风险）：上游重新导出模型会导致校验失败，
 * 届时实算新文件哈希、更新本清单并记录 CHANGELOG（清单版本化）。
 *
 * 与 R9 的偏差：R9 决议的气泡检测模型是 YOLO-seg；选型时上游项目
 * （ogkalu/comic-text-and-bubble-detector）已换代为 RT-DETR-v2 纯检测模型（无掩码），
 * 且唯一的 YOLOv8-seg 候选（kitsumed）是 GPL/AGPL 许可且 104MB。
 * RT-DETR-v2 体积 11MB、Apache-2.0、专为漫画气泡/自由文本训练（3 类），采用之。
 */
data class OcrModelSpec(
    /** 稳定标识（设置页删除/状态查询用）。 */
    val id: String,
    /** 导入时匹配的文件名（用户下载到的原始文件名）。 */
    val fileName: String,
    /** 全量 SHA-256（小写 hex，64 字符）。 */
    val sha256: String,
    /** 文件字节数（设置页展示 + 导入排障）。 */
    val sizeBytes: Long,
    /** 用途说明（设置页展示）。 */
    val purpose: String,
    val officialUrl: String,
    val mirrorUrl: String? = null,
)

object ModelCatalog {

    /** 文本检测（PaddleOCR PP-OCRv4 mobile det，中日英共用；RapidOCR v3.9.2 分发）。 */
    val DET = OcrModelSpec(
        id = "det",
        fileName = "ch_PP-OCRv4_det_mobile.onnx",
        sha256 = "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9",
        sizeBytes = 4745517L,
        purpose = "文字检测：定位页面中的文字行（中文/英文/日文/漫画通用）",
        officialUrl = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv4/det/ch_PP-OCRv4_det_mobile.onnx",
        mirrorUrl = "https://github.com/RapidAI/RapidOCR",
    )

    /** 中文识别（PP-OCRv4 mobile rec，6625 类 = blank + 6623 字 + 空格）。 */
    val REC_CH = OcrModelSpec(
        id = "rec_ch",
        fileName = "ch_PP-OCRv4_rec_mobile.onnx",
        sha256 = "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b",
        sizeBytes = 10857958L,
        purpose = "中文识别：把文字行图片转成文字（中文扫描 PDF / 中文漫画）",
        officialUrl = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv4/rec/ch_PP-OCRv4_rec_mobile.onnx",
        mirrorUrl = "https://github.com/RapidAI/RapidOCR",
    )

    /** 英文识别（97 类 = blank + 95 字 + 空格）。 */
    val REC_EN = OcrModelSpec(
        id = "rec_en",
        fileName = "en_PP-OCRv4_rec_mobile.onnx",
        sha256 = "e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318",
        sizeBytes = 7653044L,
        purpose = "英文识别（英文扫描 PDF）",
        officialUrl = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv4/rec/en_PP-OCRv4_rec_mobile.onnx",
        mirrorUrl = "https://github.com/RapidAI/RapidOCR",
    )

    /** 日文识别（PP-OCRv4 日文 rec，4401 类 = blank + 4399 字 + 空格；R8 首版语言）。 */
    val REC_JA = OcrModelSpec(
        id = "rec_ja",
        fileName = "japan_PP-OCRv4_rec_mobile.onnx",
        sha256 = "e1075a67dba758ecfc7ebc78a10ae61c95ac8fb66a9c86fab5541e33f085cb7a",
        sizeBytes = 9753335L,
        purpose = "日文识别（日漫 / 日文扫描 PDF）",
        officialUrl = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv4/rec/japan_PP-OCRv4_rec_mobile.onnx",
        mirrorUrl = "https://github.com/RapidAI/RapidOCR",
    )

    /**
     * 漫画气泡检测（RT-DETR-v2 int8；3 类：0=bubble 1=text_bubble 2=text_free）。
     *
     * 注意：int8 量化模型在 onnxruntime-mobile 精简算子包下的兼容性需真机验证
     * （含 Q/DQ 算子）；退路见 docs/AI功能需求与实施.md §风险与 CHANGELOG。
     */
    val BUBBLE = OcrModelSpec(
        id = "bubble",
        fileName = "detector-v4-s_int8.onnx",
        sha256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
        sizeBytes = 11120765L,
        purpose = "气泡检测：定位漫画对白气泡与自由文本（漫画翻译必需）",
        officialUrl = "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/main/detector-v4-s_int8.onnx",
        mirrorUrl = "https://hf-mirror.com/ogkalu/comic-text-and-bubble-detector/resolve/main/detector-v4-s_int8.onnx",
    )

    /** 全部语言识别模型。 */
    val RECS: List<OcrModelSpec> = listOf(REC_CH, REC_EN, REC_JA)

    val ALL: List<OcrModelSpec> = listOf(DET, REC_CH, REC_EN, REC_JA, BUBBLE)

    fun byFileName(name: String): OcrModelSpec? = ALL.firstOrNull { it.fileName == name }

    fun byId(id: String): OcrModelSpec? = ALL.firstOrNull { it.id == id }
}
