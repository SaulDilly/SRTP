// Fábrica de pacotes para criar pacotes SYN, ACK e FIN
public class PacketFactory {

    public static SrtpPacket createSynPacket(int tamanhoJanela) {
        SrtpPacket packet = new SrtpPacket(0);
        packet.setSyn(true);
        packet.setLength(tamanhoJanela);
        return packet;
    }

    public static SrtpPacket createSynAckPacket(int tamanhoJanela) {
        SrtpPacket packet = new SrtpPacket(0);
        packet.setSyn(true);
        packet.setAck(true);
        packet.setLength(tamanhoJanela);
        return packet;
    }

    public static SrtpPacket createAckPacket(int seq) {
        SrtpPacket packet = new SrtpPacket(0);
        packet.setAck(true);
        packet.setAckSeq(seq);
        return packet;
    }

    public static SrtpPacket createNackPacket(int seq) {
        SrtpPacket packet = new SrtpPacket(0);
        packet.setNack(true);
        packet.setAckSeq(seq);
        return packet;
    }

    public static SrtpPacket createFinPacket() {
        SrtpPacket packet = new SrtpPacket(0);
        packet.setFin(true);
        return packet;
    }

    public static SrtpPacket createFinAckPacket() {
        SrtpPacket packet = new SrtpPacket(0);
        packet.setFin(true);
        packet.setAck(true);
        return packet;
    }
}
