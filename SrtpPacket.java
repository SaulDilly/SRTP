import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

public class SrtpPacket {
    private static final int SEQ_MASK = 0x3FFF;

    private boolean syn;
    private boolean fin;
    private int seq;
    private boolean ack;
    private boolean nack;
    private int ackSeq;
    private int length;
    private long crc32;

    public SrtpPacket(int seq) {
        this.syn = false;
        this.fin = false;
        this.seq = seq;
        this.ack = false;
        this.nack = false;
        this.ackSeq = 0;
        this.length = 0;
        this.crc32 = 0L;
    }

    public boolean isSyn() {
        return syn;
    }

    public void setSyn(boolean syn) {
        this.syn = syn;
    }

    public boolean isFin() {
        return fin;
    }

    public void setFin(boolean fin) {
        this.fin = fin;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public boolean isAck() {
        return ack;
    }

    public void setAck(boolean ack) {
        this.ack = ack;
    }

    public boolean isNack() {
        return nack;
    }

    public void setNack(boolean nack) {
        this.nack = nack;
    }

    public int getAckSeq() {
        return ackSeq;
    }

    public void setAckSeq(int ackSeq) {
        this.ackSeq = ackSeq;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public byte[] toBytes() {
        return buildHeader();
    }

    /*
     * Constrói um objeto do pacote de acordo com o array de bytes recebido
     * Se o CRC for inválido, retorna null
     */
    public static SrtpPacket fromBytes(byte[] raw) {
        if (raw == null || raw.length < 9) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int firstWord = buffer.getShort() & 0xFFFF;
        int secondWord = buffer.getShort() & 0xFFFF;
        int length = buffer.get() & 0xFF;
        long receivedCrc = buffer.getInt() & 0xFFFFFFFFL;

        SrtpPacket packet = new SrtpPacket(firstWord & SEQ_MASK);
        packet.setSyn((firstWord & 0x8000) != 0);
        packet.setFin((firstWord & 0x4000) != 0);
        packet.setAck((secondWord & 0x8000) != 0);
        packet.setNack((secondWord & 0x4000) != 0);
        packet.setAckSeq(secondWord & SEQ_MASK);
        packet.setLength(length);
        packet.calculateCrc32();
        if (packet.crc32 != receivedCrc) {
            return null;
        }
        return packet;
    }

    /*
     * Constrói o cabeçalho em bytes com os dados atuais do objeto
     */
    private byte[] buildHeader() {
        return buildHeader(true);
    }

    /*
     * Calcula o CRC32 com base nos dados atuais do objeto
     */
    public void calculateCrc32() {
        // Constrói o cabeçalho em bytes para o CRC (sem considerar o campo de CRC)
        byte[] header = buildHeader(false);
        CRC32 crc = new CRC32();
        crc.update(header, 0, header.length);
        this.crc32 = crc.getValue();
    }

    /*
     * Constrói o cabeçalho em bytes com os dados atuais do objeto
     */
    private byte[] buildHeader(boolean includeCrc) {
        ByteBuffer buffer = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) buildFirstByte());
        buffer.putShort((short) buildSecondByte());
        buffer.put((byte) (length & 0xFF));
        buffer.putInt(includeCrc ? (int) crc32 : 0);
        return buffer.array();
    }

    /*
     * Constrói o primeiro byte do cabeçalho: SYN, FIN e SEQ
     */
    private int buildFirstByte() {
        int value = 0;
        value = (value << 1) | bit(syn);
        value = (value << 1) | bit(fin);
        value = (value << 14) | (seq & SEQ_MASK);
        return value;
    }

    /*
     * Constrói o primeiro byte do cabeçalho: ACK, NACK e ACK_SEQ
     */
    private int buildSecondByte() {
        int value = 0;
        value = (value << 1) | bit(ack);
        value = (value << 1) | bit(nack);
        value = (value << 14) | (ackSeq & SEQ_MASK);
        return value;
    }

    private int bit(boolean flag) {
        return flag ? 1 : 0;
    }
}