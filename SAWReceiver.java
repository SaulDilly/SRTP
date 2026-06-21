import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.io.FileOutputStream;
import java.net.SocketTimeoutException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class SAWReceiver implements ReceiverInterface{

    private static final int SEQ_MASK = 0x3FFF;
    private static final int TIMEOUT = 100;
    private static final int MAX_PAYLOAD = 255;
    private static final int HEADER_SIZE = 9;

    /* 
     * Recebe o arquivo enviado pelo sender, pacote a pacote, enviando um ACK para cada pacote recebido 
     */
    public void receiveFile(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            receiveFile(socket);
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao receber a conexão via UDP", exception);
        }
    }

    private void receiveFile(DatagramSocket socket) throws Exception {
        ByteArrayOutputStream applicationBuffer = new ByteArrayOutputStream();
        socket.setSoTimeout(TIMEOUT);
        byte[] receiveBuffer = new byte[HEADER_SIZE + MAX_PAYLOAD];
        int lastSeqRead = SEQ_MASK;

        // Recebe os pacotes do sender
        while (true) {
            DatagramPacket dataDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            Log.writeLine("Aguardando pacote de dados...");

            // Recebe os pacotes, com proteção de timeout para encerrar a recepção caso o sender finalize a transmissão e não envie mais pacotes
            try {
                socket.receive(dataDatagram);
            } catch (SocketTimeoutException timeoutException) {
                continue;
            }

            // Converte o pacote recebido para o objeto de pacote, validando o CRC32 e a estrutura do pacote
            SrtpPacket dataPacket = SrtpPacket.fromBytes(Arrays.copyOf(dataDatagram.getData(), dataDatagram.getLength()));
            if (dataPacket == null || dataPacket.isSyn() || dataPacket.isAck() || dataPacket.isNack()) {
                Log.writeLine("Pacote de dados inválido.");
                continue;
            }

            // Se é pacote de finalização de transmissão, salva o arquivo e encerra o aguardo de mensagens
            if (dataPacket.isFin()) {
                Log.writeLine("Push da aplicação com " + applicationBuffer.size() + " bytes.");
                try (FileOutputStream outputStream = new FileOutputStream("received.txt")) {
                    outputStream.write(applicationBuffer.toByteArray());
                    outputStream.flush();
                }
                applicationBuffer.reset();

                // Envia FIN+ACK de resposta para o sender, para confirmar o recebimento do FIN e a finalização da transmissão
                SrtpPacket finAckPacket = PacketFactory.createFinAckPacket();
                finAckPacket.calculateCrc32();
                byte[] finAckBytes = finAckPacket.toBytes();
                DatagramPacket finAckResponse = new DatagramPacket(
                        finAckBytes,
                        finAckBytes.length,
                        dataDatagram.getAddress(),
                        dataDatagram.getPort());
                socket.send(finAckResponse);
                break;
            }
            
            // O tamanho do datagrama deve ser o cabeçalho + payload
            int payloadLength = dataPacket.getLength();
            if (dataDatagram.getLength() < HEADER_SIZE + payloadLength) {
                Log.writeLine("Pacote de dados incompleto.");
                continue;
            }
            
            // Copia para o buffer da aplicação somente o payload, somente se for de uma sequência esperada
            byte[] payload = Arrays.copyOfRange(dataDatagram.getData(), HEADER_SIZE, HEADER_SIZE + payloadLength);
            int expectedSeq = (lastSeqRead + 1) & SEQ_MASK;

            // Se recebeu a mesma sequência do último pacote lido, é um pacote duplicado, então não escreve no buffer da aplicação, mas reenvia o ACK equivalente
            if (dataPacket.getSeq() == lastSeqRead) {
                Log.writeLine("Pacote duplicado recebido para seq=" + dataPacket.getSeq() + ", reenviando ACK equivalente.");
            } else if (dataPacket.getSeq() == expectedSeq) { // Se é a sequência esperada, escreve no buffer da aplicação e atualiza o último número de sequência lido
                if (dataPacket.getLength() > 0) {
                    applicationBuffer.write(payload);
                }
                lastSeqRead = dataPacket.getSeq();
            } else { // Se o pacote tem o CRC32 válido, mas a sequência é diferente do esperado, envia NACK
                Log.writeLine("Sequência inesperada recebida: " + dataPacket.getSeq() + ", enviando NACK para seq=" + expectedSeq);
                int nackSeqToSend = expectedSeq;
                SrtpPacket nackPacket = PacketFactory.createNackPacket(nackSeqToSend);
                nackPacket.calculateCrc32();
                byte[] nackBytes = nackPacket.toBytes();
                DatagramPacket nackResponse = new DatagramPacket(
                        nackBytes,
                        nackBytes.length,
                        dataDatagram.getAddress(),
                        dataDatagram.getPort());
                socket.send(nackResponse);
                continue;
            }

            // Envia o ACK de volta para o sender, com o número de sequência do pacote recebido
            int ackSeqToSend = lastSeqRead;
            SrtpPacket ackPacket = PacketFactory.createAckPacket(ackSeqToSend);
            ackPacket.calculateCrc32();
            byte[] ackBytes = ackPacket.toBytes();
            DatagramPacket ackResponse = new DatagramPacket(
                    ackBytes,
                    ackBytes.length,
                    dataDatagram.getAddress(),
                    dataDatagram.getPort());
            socket.send(ackResponse);
            Log.writeLine("ACK enviado para seq=" + ackSeqToSend);
        }
    }
    
}
