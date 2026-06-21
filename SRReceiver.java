import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.io.FileOutputStream;
import java.net.SocketTimeoutException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class SRReceiver implements ReceiverInterface {

    private static final int SEQ_MASK = 0x3FFF;
    private static final int TIMEOUT = 100;
    private static final int MAX_PAYLOAD = 255;
    private static final int HEADER_SIZE = 9;

    private final int windowLength;
    
    // Arrays para gerenciar o buffer da janela de recepção
    private byte[][] receiveWindow;
    private boolean[] received;
    private int baseSeq; // A sequência esperada para o início da janela

    public SRReceiver(int windowLength) {
        if (windowLength <= 0) {
            throw new IllegalArgumentException("O tamanho da janela deve ser maior que 0.");
        }
        this.windowLength = windowLength;
        this.receiveWindow = new byte[windowLength][];
        this.received = new boolean[windowLength];
        this.baseSeq = 0; // SR sempre começa esperando o pacote 0
    }

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

        Log.writeLine("Aguardando conexões SR (Janela = " + windowLength + " pacotes)...");

        while (true) {
            DatagramPacket dataDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            
            try {
                socket.receive(dataDatagram);
            } catch (SocketTimeoutException e) {
                continue; // Fica ouvindo indefinidamente até o fluxo terminar
            }

            InetAddress senderAddress = dataDatagram.getAddress();
            int senderPort = dataDatagram.getPort();

            SrtpPacket dataPacket = SrtpPacket.fromBytes(Arrays.copyOf(dataDatagram.getData(), dataDatagram.getLength()));
            
            if (dataPacket == null || dataPacket.isSyn() || dataPacket.isAck() || dataPacket.isNack()) {
                Log.writeLine("Pacote corrompido ou com CRC inválido recebido e ignorado.");
                continue;
        }

            // Se é pacote de finalização (FIN)
            if (dataPacket.isFin()) {
                Log.writeLine("FIN recebido. Push da aplicação com " + applicationBuffer.size() + " bytes.");
                try (FileOutputStream outputStream = new FileOutputStream("received.txt")) {
                    outputStream.write(applicationBuffer.toByteArray());
                    outputStream.flush();
                }
                applicationBuffer.reset();

                // Confirma o FIN com FIN+ACK
                SrtpPacket finAckPacket = PacketFactory.createFinAckPacket();
                finAckPacket.calculateCrc32();
                byte[] finAckBytes = finAckPacket.toBytes();
                socket.send(new DatagramPacket(finAckBytes, finAckBytes.length, senderAddress, senderPort));
                return; 
            }

            int payloadLength = dataPacket.getLength();
            if (dataDatagram.getLength() < HEADER_SIZE + payloadLength) {
                Log.writeLine("Pacote de dados incompleto.");
                continue;
            }

            int seq = dataPacket.getSeq();
            
            // Calcula a distância do pacote recebido em relação à base da janela
            int dist = (seq - baseSeq) & SEQ_MASK;

            // Se o pacote pertence à janela atual
            if (dist < windowLength) {
                
                // Se é um pacote novo dentro da janela
                if (!received[dist]) {
                    Log.writeLine("Pacote aceito na janela (seq=" + seq + "). Enviando ACK individual.");
                    
                    // Bufferiza o pacote e marca como recebido
                    received[dist] = true;
                    receiveWindow[dist] = Arrays.copyOfRange(dataDatagram.getData(), HEADER_SIZE, HEADER_SIZE + payloadLength);
                    
                    // Envia ACK Individual
                    sendAck(socket, seq, senderAddress, senderPort);

                    // Se dist > 0, significa que recebemos um pacote fora de ordem (à frente da base).
                    // Portanto, há pacotes faltando antes dele. Disparamos NACK para essas lacunas.
                    if (dist > 0) {
                        for (int i = 0; i < dist; i++) {
                            if (!received[i]) {
                                int missingSeq = (baseSeq + i) & SEQ_MASK;
                                Log.writeLine("Lacuna detectada. Enviando NACK para o pacote faltante seq=" + missingSeq);
                                sendNack(socket, missingSeq, senderAddress, senderPort);
                            }
                        }
                    }

                    // A janela só avança se o pacote da 'base' (índice 0) chegou
                    if (received[0]) {
                        int shiftCount = 0;
                        while (shiftCount < windowLength && received[shiftCount]) {
                            // Escreve os dados em ordem no buffer da aplicação
                            if (receiveWindow[shiftCount] != null && receiveWindow[shiftCount].length > 0) {
                                applicationBuffer.write(receiveWindow[shiftCount]);
                            }
                            shiftCount++;
                        }

                        Log.writeLine("Base completada. Deslizando a janela do receptor em " + shiftCount + " pacote(s).");

                        // Puxa os pacotes pendentes para o início
                        for (int i = 0; i < windowLength - shiftCount; i++) {
                            receiveWindow[i] = receiveWindow[i + shiftCount];
                            received[i] = received[i + shiftCount];
                        }
                        
                        // Limpa os espaços que ficaram livres no final
                        for (int i = windowLength - shiftCount; i < windowLength; i++) {
                            receiveWindow[i] = null;
                            received[i] = false;
                        }

                        baseSeq = (baseSeq + shiftCount) & SEQ_MASK;
                    }

                } else {
                    // Pacote duplicado que já está no nosso buffer
                    Log.writeLine("Pacote duplicado dentro da janela (seq=" + seq + "). Reenviando ACK.");
                    sendAck(socket, seq, senderAddress, senderPort);
                }

            } 
            // No protocolo SR, se a distância de trás pra frente couber na janela, 
            // trata-se de um pacote ANTIGO cujo ACK se perdeu e o sender retransmitiu.
            else {
                int oldDist = (baseSeq - seq) & SEQ_MASK;
                if (oldDist <= windowLength && oldDist > 0) {
                    Log.writeLine("Pacote antigo/já processado recebido (seq=" + seq + "). Reenviando ACK para destravar o Sender.");
                    sendAck(socket, seq, senderAddress, senderPort);
                } else {
                    Log.writeLine("Pacote fora dos limites aceitáveis (seq=" + seq + "). Ignorado.");
                }
            }
        }
    }

    private void sendAck(DatagramSocket socket, int seq, InetAddress address, int port) throws Exception {
        SrtpPacket ackPacket = PacketFactory.createAckPacket(seq);
        ackPacket.calculateCrc32();
        byte[] ackBytes = ackPacket.toBytes();
        socket.send(new DatagramPacket(ackBytes, ackBytes.length, address, port));
    }

    private void sendNack(DatagramSocket socket, int seq, InetAddress address, int port) throws Exception {
        SrtpPacket nackPacket = PacketFactory.createNackPacket(seq);
        nackPacket.calculateCrc32();
        byte[] nackBytes = nackPacket.toBytes();
        socket.send(new DatagramPacket(nackBytes, nackBytes.length, address, port));
    }
}