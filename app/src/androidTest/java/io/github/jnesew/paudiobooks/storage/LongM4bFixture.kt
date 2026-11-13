package io.github.jnesew.paudiobooks.storage

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic MP4 index stress case. Sparse payload is not a 34-hour acoustic fixture. */
internal object LongM4bFixture {
    const val SAMPLES = 5_272_000
    const val MOOV_OFFSET = 2_400_000_000L
    const val DURATION_MS = SAMPLES * 1024L * 1000 / 44100
    private fun ints(vararg n: Int) = ByteBuffer.allocate(n.size*4).order(ByteOrder.BIG_ENDIAN).apply { n.forEach { putInt(it) } }.array()
    private fun box(type: String, payload: ByteArray) = ints(payload.size+8)+type.toByteArray(Charsets.ISO_8859_1)+payload
    private fun children(bytes: ByteArray, start: Int = 8): List<ByteArray> {
        val result=mutableListOf<ByteArray>(); var at=start
        while (at<bytes.size) {
            val size=ByteBuffer.wrap(bytes,at,4).int
            require(size>=8 && size<=bytes.size-at)
            result+=bytes.copyOfRange(at,at+size);at+=size
        }
        return result
    }
    private fun join(parts: List<ByteArray>): ByteArray = java.io.ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    fun write(file: File, base: ByteArray, png: ByteArray) {
        require(base.size<256*1024 && png.size<64*1024)
        val top=children(ints(base.size+8)+"root".toByteArray()+base)
        val ftyp=top.single { String(it,4,4)=="ftyp" }
        val covr=box("covr",box("data",ints(14,0)+png))
        fun rebuild(raw: ByteArray): ByteArray {
            val type=String(raw,4,4,Charsets.ISO_8859_1)
            return when(type) {
                "moov","trak","mdia","minf","stbl","udta" -> box(type,children(raw).filterNot {
                    String(it,4,4) in setOf("edts","sgpd","sbgp")
                }.map { rebuild(it) }.let(::join))
                "meta" -> box(type,raw.copyOfRange(8,12)+join(children(raw,12).map { rebuild(it) }))
                "ilst" -> box(type,raw.copyOfRange(8,raw.size)+covr)
                "stsz" -> box(type,ByteBuffer.allocate(12+SAMPLES*4).apply {
                    putInt(0);putInt(0);putInt(SAMPLES);repeat(SAMPLES) { putInt(400+it%2) }
                }.array())
                "stts" -> box(type,ints(0,1,SAMPLES,1024))
                "stsc" -> box(type,ints(0,1,1,SAMPLES,1))
                "stco" -> box(type,ints(0,1,ftyp.size+8))
                "mdhd" -> box(type,ByteBuffer.allocate(36).apply {
                    putInt(0x01000000);putLong(0);putLong(0);putInt(44100);putLong(SAMPLES.toLong()*1024);putShort(0x55C4.toShort());putShort(0)
                }.array())
                "mvhd" -> raw.copyOf().also { ByteBuffer.wrap(it).apply { putInt(20,1000);putInt(24,DURATION_MS.toInt()) } }
                "tkhd" -> raw.copyOf().also { ByteBuffer.wrap(it).putInt(28,DURATION_MS.toInt()) }
                else -> raw
            }
        }
        val moov=rebuild(top.single { String(it,4,4)=="moov" })
        require(moov.size>16*1024*1024 && moov.size<32*1024*1024)
        RandomAccessFile(file,"rw").use {
            it.setLength(0);it.write(ftyp);it.write(ints((MOOV_OFFSET-ftyp.size).toInt()));it.writeBytes("mdat")
            it.seek(MOOV_OFFSET);it.write(moov)
        }
    }
}
