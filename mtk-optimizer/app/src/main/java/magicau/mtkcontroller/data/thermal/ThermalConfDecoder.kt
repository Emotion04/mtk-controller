package magicau.mtkcontroller.data.thermal

/**
 * vivo `/vendor/etc/thermal/` 下 thermal_policy_NN.conf 的解码器。
 *
 * 编码方式（2026-09-25 真机实测得出）：
 * 逐行 Vigenère 变体，密钥 = 行内字符下标 % 10，字母表 `0x20–0x7A`
 * （91 个可打印 ASCII），换行符原样透传并**把下标复位为 0**。
 *
 * 这里内置的是**算法**，不是任何一份配置的内容。
 * 内容每次从设备上现读现解 —— 这样系统更新了配置、或者换了机型，
 * 展示的都还是设备当下的事实，不会是过期写死的描述。
 *
 * 解不开（比如厂商换了混淆方式）时返回null，由调用方退回"只显示编号"。
 */
object ThermalConfDecoder {

    private const val ALPHABET = 91
    private const val LONG_BASE = 0x20
    private const val LONG_KEY = 10
    private const val LONG_NEWLINE = 0x0A

    /** 解码一段混淆文本。返回 null 表示解开的结果不含可读内容。 */
    fun decode(text: String): String? {
        if (text.isEmpty()) return null
        val out = StringBuilder(text.length)
        var index = 0
        for (ch in text) {
            val code = ch.code
            if (code == LONG_NEWLINE) {
                out.append('\n')
                index = 0
                continue
            }
            if (code < LONG_BASE || code >= LONG_BASE + ALPHABET) {
                // 落在字母表外的字节说明输入不是这套编码，整段判失败。
                return null
            }
            val plain = LONG_BASE + (code - LONG_BASE - (index % LONG_KEY) + ALPHABET * 2) % ALPHABET
            out.append(plain.toChar())
            index++
        }
        // 只保证字符落在字母表内。**不**在这里判断"解对了吗" ——
        // 一段合法明文同样落在字母表内，靠输出形状判不出来。
        // 是不是这份配置，由调用方做结构校验（见 ThermalPolicyReader.read）。
        return out.toString()
    }
}
