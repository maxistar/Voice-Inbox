package me.maxistar.voiceinbox

import java.io.File
import java.io.RandomAccessFile

class PcmWavWriter(
    private val temporaryFile: File,
    private val finalFile: File,
    private val sampleRate: Int = SAMPLE_RATE,
    private val channelCount: Short = 1,
) {
    private var file: RandomAccessFile? = null
    private var dataBytes = 0L

    fun open() {
        check(file == null) { "WAV writer is already open" }
        temporaryFile.parentFile?.mkdirs()
        temporaryFile.delete()
        file = RandomAccessFile(temporaryFile, "rw").also { output ->
            output.setLength(0L)
            writeHeader(output, dataSize = 0L)
        }
    }

    fun append(samples: ShortArray, count: Int = samples.size) {
        require(count in 0..samples.size) { "Invalid PCM sample count" }
        val output = file ?: error("WAV writer is not open")
        repeat(count) { index ->
            val sample = samples[index].toInt()
            output.write(sample and 0xff)
            output.write((sample ushr 8) and 0xff)
        }
        dataBytes += count.toLong() * BYTES_PER_SAMPLE
    }

    fun finish(): Long {
        val output = file ?: error("WAV writer is not open")
        return try {
            output.seek(0L)
            writeHeader(output, dataBytes)
            output.setLength(HEADER_SIZE + dataBytes)
            output.fd.sync()
            output.close()
            file = null
            check(temporaryFile.renameTo(finalFile)) {
                "Unable to finalize recorded audio"
            }
            dataBytes
        } catch (error: Throwable) {
            runCatching { output.close() }
            file = null
            abortFiles()
            throw error
        }
    }

    fun abort() {
        runCatching { file?.close() }
        file = null
        abortFiles()
    }

    internal fun finalFileForResult(): File = finalFile

    private fun abortFiles() {
        temporaryFile.delete()
        finalFile.delete()
    }

    private fun writeHeader(output: RandomAccessFile, dataSize: Long) {
        require(dataSize in 0..MAX_DATA_SIZE) { "WAV data is too large" }
        val byteRate = sampleRate.toLong() * channelCount.toLong() * BYTES_PER_SAMPLE
        val blockAlign = (channelCount.toInt() * BYTES_PER_SAMPLE).toShort()
        output.writeAscii("RIFF")
        output.writeLittleEndianInt((HEADER_SIZE - 8L + dataSize).toInt())
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeLittleEndianInt(16)
        output.writeLittleEndianShort(1)
        output.writeLittleEndianShort(channelCount)
        output.writeLittleEndianInt(sampleRate)
        output.writeLittleEndianInt(byteRate.toInt())
        output.writeLittleEndianShort(blockAlign)
        output.writeLittleEndianShort((BYTES_PER_SAMPLE * 8).toShort())
        output.writeAscii("data")
        output.writeLittleEndianInt(dataSize.toInt())
    }

    private fun RandomAccessFile.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Short) {
        write(value.toInt() and 0xff)
        write((value.toInt() ushr 8) and 0xff)
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val HEADER_SIZE = 44L
        private const val MAX_DATA_SIZE = 0xffff_ffffL
    }
}
