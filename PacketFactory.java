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
        packet.setAckSeq(0);
        packet.setLength(tamanhoJanela);
        return packet;
    }

    public static SrtpPacket createAckPacket() {
        SrtpPacket packet = new SrtpPacket(0);
        packet.setAck(true);
        packet.setAckSeq(0);
        packet.setLength(0);
        return packet;
    }
}
