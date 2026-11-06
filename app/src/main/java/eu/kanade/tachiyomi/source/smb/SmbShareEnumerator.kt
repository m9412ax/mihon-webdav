package eu.kanade.tachiyomi.source.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet

/**
 * SY --> 用 SRVSVC 的 `NetrShareEnum` 枚举服务器上的共享。
 *
 * 为什么得自己写：smbj 只做到「连接某个已知共享」，没有 SRVSVC 客户端（jar 里
 * `NetrShareEnum` / `srvsvc` 出现 0 次）。管道传输它是有提供的 ——
 * `PipeShare.open()` + `NamedPipe.transact()` 内部就是 `FSCTL_PIPE_TRANSCEIVE`，
 * 缺的只有 RPC PDU 封装与 NDR 编解码这两层。
 *
 * 字节布局对齐 jCIFS（`org.codelibs.jcifs`，jcifs-ng 的维护分支）里久经实战的实现：
 *  - RPC 公共头 16 字节（`DcerpcMessage.encode_header`）
 *  - BIND PDU 体 57 字节（`DcerpcBind.encode_in` 的字段顺序）
 *  - `NetrShareEnum` opnum = 0x0F，参数顺序见 `srvsvc.ShareEnumAll.encode_in`
 *
 * ⚠️ deferred（指针指涉数据）究竟插在哪里，jCIFS 的字面写法与 MS-NDR 规范描述不一致
 * （它把 out 参数也写进了 deferred 缓冲区）。响应侧不赌某一种：三个标量 out 参数
 * 从**尾部倒取**，容器从 `info` 指针之后的中间段解析 —— 两种约定下这两段互不重叠。
 *
 * ⚠️ 只在「设置 → 选择共享」里被调用，不参与任何读取路径；失败一律返回
 * [Result.failure] 并带上可诊断的原因，不影响「手填共享名」这条已经能用的路径。
 */
internal object SmbShareEnumerator {

    /**
     * 一个共享。
     *
     * [isDiskTree]：STYPE_DISKTREE = 0x00000000，低 5 位为 0 才是可浏览的文件共享；
     * IPC$（0x3）、打印队列（0x1）等都落在非零位上，应当排除。
     * 管理性共享（C$ / ADMIN$ / D$）类型上仍是 DISKTREE，靠结尾的 `$` 识别。
     */
    data class Share(val name: String, val type: Long, val remark: String) {
        val isDiskTree: Boolean get() = (type and 0x0000001FL) == 0L

        /** 隐藏的管理共享（结尾带 $），列给用户看只会造成困扰。 */
        val isAdminShare: Boolean get() = name.endsWith("$")
    }

    private const val PTYPE_REQUEST = 0
    private const val PTYPE_RESPONSE = 3
    private const val PTYPE_BIND = 11
    private const val PTYPE_BIND_ACK = 12
    private const val FLAGS_FIRST_LAST = 0x03
    private const val DREP_LITTLE_ENDIAN = 0x00000010
    private const val HEADER_LEN = 16

    /** REQUEST 与 RESPONSE 都是「16 字节公共头 + 4 字节 u2/保留 + 4 字节 alloc_hint」后才进 stub。 */
    private const val STUB_OFFSET = 24

    private const val OPNUM_NETR_SHARE_ENUM = 0x0F
    private const val MAX_PREFERRED_LENGTH = -1 // 0xFFFFFFFF：让服务器一次尽量多发

    private const val SRVSVC_UUID = "4b324fc8-1670-01d3-1278-5a47bf6ee188"
    private const val SRVSVC_VERSION_MAJOR = 3
    private const val SRVSVC_VERSION_MINOR = 0
    private const val NDR_SYNTAX_UUID = "045d888a-eb1c-c380-0002-120501040000"
    private const val NDR_SYNTAX_VERSION = 2

    private const val MAX_FRAG = 4280
    private const val READ_BUFFER = 64 * 1024
    private const val MAX_SHARES = 4096

    /**
     * 连 `IPC$` → 开 `srvsvc` 管道 → bind → `NetrShareEnum(level 1)`。
     *
     * @param host 用来拼 ServerName。MS-SRVS 说该参数可以为 NULL 表示本机，但实测
     *             有服务器对 NULL 回 STATUS_INVALID_PARAMETER，所以照 jCIFS 传 `\\host`。
     */
    fun enumerate(session: Session, host: String): Result<List<Share>> = runCatching {
        val ipc = session.connectShare("IPC$")
        if (ipc !is PipeShare) {
            throw IllegalStateException(
                "IPC\$ 不是命名管道共享（实际 ${ipc.javaClass.simpleName}）——服务器可能禁用了 IPC\$ " +
                    "或不允许当前账号枚举共享",
            )
        }
        val pipe = ipc.open(
            "srvsvc",
            SMB2ImpersonationLevel.Impersonation,
            EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        try {
            requireBindAck(transact(pipe, pdu(PTYPE_BIND, callId = 1, bindBody())))
            parseShareEnum(transact(pipe, shareEnumPdu(host, callId = 2)))
        } finally {
            runCatching { pipe.close() }
        }
    }

    // ===================== 传输 =====================

    /**
     * 一次 RPC 事务。响应大于单次 TRANSCEIVE 返回量时继续补读，直到凑够头部声明的
     * frag_length —— 共享多的时候不补读就会只解出半截数据。
     */
    private fun transact(pipe: NamedPipe, pdu: ByteArray): ByteArray {
        var received = pipe.transact(pdu)
        if (received.isEmpty()) return received
        val declared = if (received.size >= HEADER_LEN) u16(received, 8) else Int.MAX_VALUE
        var guard = 0
        while (received.size in 1 until declared && guard++ < 64) {
            val more = ByteArray(READ_BUFFER)
            val n = runCatching { pipe.read(more, 0, more.size) }.getOrDefault(-1)
            if (n <= 0) break
            received += more.copyOf(n)
        }
        return received
    }

    private fun requireBindAck(ack: ByteArray) {
        if (ack.size < HEADER_LEN) throw IllegalStateException("bind 响应过短（${ack.size} 字节）")
        val ptype = ack[2].toInt() and 0xFF
        if (ptype != PTYPE_BIND_ACK) {
            throw IllegalStateException("bind 没有得到 ACK（ptype=$ptype，期望 $PTYPE_BIND_ACK）")
        }
        // result 的位置随 secondary address 长度浮动：16 头 + 2 + 2 + 4 + [sec_addr_len] → align4
        var i = 16 + 2 + 2 + 4
        if (i + 2 > ack.size) return
        val secAddrLen = u16(ack, i)
        i += 2 + secAddrLen
        i = (i + 3) / 4 * 4
        if (i + 2 <= ack.size) {
            val result = u16(ack, i)
            if (result != 0) {
                throw IllegalStateException("SRVSVC bind 被拒绝，result=$result（一般是接口版本不被支持）")
            }
        }
    }

    // ===================== PDU 构造 =====================

    private fun pdu(ptype: Int, callId: Int, body: ByteArray): ByteArray {
        val total = HEADER_LEN + body.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(5) // rpc_vers
        buf.put(0) // rpc_vers_minor
        buf.put(ptype.toByte())
        buf.put(FLAGS_FIRST_LAST.toByte())
        buf.putInt(DREP_LITTLE_ENDIAN)
        buf.putShort(total.toShort())
        buf.putShort(0) // auth_length：不带认证验证器
        buf.putInt(callId)
        buf.put(body)
        return buf.array()
    }

    /** BIND 体，字段顺序严格照 `DcerpcBind.encode_in`。 */
    private fun bindBody(): ByteArray {
        val b = ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(MAX_FRAG.toShort()) // max_xmit
        b.putShort(MAX_FRAG.toShort()) // max_recv
        b.putInt(0) // assoc_group
        b.put(1) // num context items
        b.put(0) // reserved
        b.putShort(0) // reserved2
        b.putShort(0) // context id
        b.put(1) // num transfer syntaxes
        b.putShort(0) // reserved
        putUuid(b, SRVSVC_UUID)
        b.putShort(SRVSVC_VERSION_MAJOR.toShort())
        b.putShort(SRVSVC_VERSION_MINOR.toShort())
        putUuid(b, NDR_SYNTAX_UUID)
        b.putInt(NDR_SYNTAX_VERSION)
        return b.array()
    }

    /** UUID 的 mixed-endian 编码：前三个字段小端，末 8 字节原样。 */
    private fun putUuid(b: ByteBuffer, uuid: String) {
        val hex = uuid.replace("-", "")
        b.putInt(hex.substring(0, 8).toLong(16).toInt())
        b.putShort(hex.substring(8, 12).toLong(16).toShort())
        b.putShort(hex.substring(12, 16).toLong(16).toShort())
        for (i in 16 until 32 step 2) {
            b.put(hex.substring(i, i + 2).toLong(16).toByte())
        }
    }

    /**
     * `NetrShareEnum` 请求。入参容器给空（count=0、array=NULL），
     * 由服务器决定返回量、prefmaxlen 兜上限。
     */
    private fun shareEnumPdu(host: String, callId: Int): ByteArray {
        val stub = NdrWriter()
        stub.u32(1) // ServerName referent
        stub.ndrString("\\\\$host")
        stub.u32(1) // level
        stub.u32(1) // union discriminant
        stub.u32(1) // info referent
        stub.u32(0) // container.count
        stub.u32(0) // container.array referent = NULL
        stub.u32(MAX_PREFERRED_LENGTH)
        stub.u32(0) // resume_handle

        val stubBytes = stub.toByteArray()
        val body = ByteBuffer.allocate(8 + stubBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        // alloc_hint = 从 alloc_hint 之后算起的字节数；这里与 jCIFS 一致取「总长 - 16」
        body.putInt(HEADER_LEN + 8 + stubBytes.size - HEADER_LEN)
        body.putShort(0) // context id
        body.putShort(OPNUM_NETR_SHARE_ENUM)
        body.put(stubBytes)
        return pdu(PTYPE_REQUEST, callId, body.array())
    }

    // ===================== 响应解析 =====================

    private fun parseShareEnum(response: ByteArray): List<Share> {
        if (response.size < STUB_OFFSET + 24) {
            throw IllegalStateException("响应过短（${response.size} 字节），可能被管道分片截断了")
        }
        val ptype = response[2].toInt() and 0xFF
        if (ptype != PTYPE_RESPONSE) throw IllegalStateException("不是 RESPONSE PDU（ptype=$ptype）")

        val r = NdrReader(response, STUB_OFFSET)
        val level = r.u32()
        r.u32() // union discriminant
        val infoRef = r.u32()
        val retval = u32(response, response.size - 4)
        if (retval != 0) {
            throw IllegalStateException(
                "NetrShareEnum 返回 0x%08x（0x00000005=访问被拒，0x00000001=函数无效，" +
                    "0x00000032=共享不存在或不可达）".format(retval),
            )
        }
        if (infoRef == 0) return emptyList()
        if (level != 1) throw IllegalStateException("服务器回的是 level=$level，本实现只解析 level 1")

        // 容器从 info_ref 之后开始，到尾部三个 u32（totalentries / resume / retval）之前结束
        val containerStart = r.index
        val containerEnd = response.size - 12
        if (containerEnd <= containerStart + 8) return emptyList()

        val c = NdrReader(response, containerStart)
        val count = c.u32()
        val arrayRef = c.u32()
        if (arrayRef == 0 || count <= 0) return emptyList()
        if (count > MAX_SHARES) throw IllegalStateException("声明了 $count 个共享，超过安全上限 $MAX_SHARES")

        val maxCount = c.u32()
        if (maxCount < count) throw IllegalStateException("数组 max_count=$maxCount 小于 count=$count")

        // 先读走 12*count 个描述符，字符串数据在描述符区之后按序 deferred
        val descriptors = ArrayList<Triple<Int, Long, Int>>(count)
        repeat(count) {
            val netnameRef = c.u32()
            val type = c.u32().toLong() and 0xFFFFFFFFL
            val remarkRef = c.u32()
            descriptors += Triple(netnameRef, type, remarkRef)
        }

        return descriptors.mapNotNull { (netnameRef, type, remarkRef) ->
            if (netnameRef == 0) return@mapNotNull null
            val name = c.ndrString()
            val remark = if (remarkRef != 0) c.ndrString() else ""
            Share(name = name, type = type, remark = remark).takeIf { it.name.isNotBlank() }
        }
    }

    // ===================== NDR 读写原语 =====================

    private class NdrWriter {
        private val bytes = ArrayList<Byte>()

        fun u32(value: Int) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
            b.forEach { bytes.add(it) }
        }

        /** conformant/varying 字符串：max_count、offset、actual_count、UTF-16LE 码元（含 NUL）、align(4)。 */
        fun ndrString(value: String) {
            val chars = value.toCharArray()
            u32(chars.size + 1) // max_count
            u32(0) // offset
            u32(chars.size + 1) // actual_count
            val b = ByteBuffer.allocate(chars.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            chars.forEach { b.putShort(it.code.toShort()) }
            b.array().forEach { bytes.add(it) }
            bytes.add(0)
            bytes.add(0) // 结尾 NUL
            repeat((4 - bytes.size % 4) % 4) { bytes.add(0) }
        }

        fun toByteArray(): ByteArray = ByteArray(bytes.size) { bytes[it] }
    }

    private class NdrReader(private val buf: ByteArray, var index: Int) {

        /** 读一个 uint32 并前进 4 字节；越界直接抛，避免把截断的响应当成合法数据解。 */
        fun u32(): Int {
            require(index + 4 <= buf.size) { "NDR 读取越界：需要 4 字节，剩余 ${buf.size - index}" }
            val v = u32(buf, index)
            index += 4
            return v
        }

        fun align4() {
            index = (index + 3) / 4 * 4
        }

        /** 与 [NdrWriter.ndrString] 对称：max_count、offset、actual_count、UTF-16LE 码元、align(4)。 */
        fun ndrString(): String {
            val maxCount = u32()
            u32() // offset
            val actualCount = u32()
            if (actualCount < 0 || index + actualCount * 2 > buf.size) {
                throw IllegalStateException(
                    "NDR 字符串越界：max_count=$maxCount actual_count=$actualCount，剩余 ${buf.size - index} 字节",
                )
            }
            val chars = CharArray(actualCount) { i -> u16(buf, index + i * 2).toChar() }
            index += actualCount * 2
            align4()
            // actual_count 含结尾 NUL，去掉它以及任何内嵌 NUL
            return String(chars).trimEnd('\u0000')
        }
    }

}

// u16 / u32 必须是**文件级**私有函数：NdrReader 是嵌套类而非 inner class，
// 拿不到外层 object 的成员，写成对象成员会直接编译不过。
private fun u16(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

private fun u32(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xFF) or
        ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or
        ((b[at + 3].toInt() and 0xFF) shl 24)
