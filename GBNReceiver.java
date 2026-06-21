import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.io.FileOutputStream;
import java.net.SocketTimeoutException;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class GBNReceiver implements ReceiverInterface {

    private static final int SEQ_MASK = 0x3FFF;
    private static final int TIMEOUT = 100;
    private static final int MAX_PAYLOAD = 255;
    private static final int HEADER_SIZE = 9;

    private final int windowLength;

    // Construtor recebe a quantidade de pacotes que deve esperar (tamanho da janela)
    public GBNReceiver(int windowLength) {
        if (windowLength <= 0) {
            throw new IllegalArgumentException("O tamanho da janela deve ser maior que 0.");
        }
        this.windowLength = windowLength;
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
        
        // Em GBN, o receiver começa esperando estritamente a sequência 0
        int expectedSeq = 0; 

        Log.writeLine("Aguardando conexões (Janela = " + windowLength + " pacotes)...");

        // Loop externo: continua até o arquivo ser finalizado
        while (true) {
            int packetsReceivedInBatch = 0;
            boolean sendNack = false;
            InetAddress senderAddress = null;
            int senderPort = -1;

            // Loop interno: tenta ler a rajada de pacotes até o limite da janela
            for (int i = 0; i < windowLength; i++) {
                DatagramPacket dataDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                
                try {
                    socket.receive(dataDatagram);
                    senderAddress = dataDatagram.getAddress();
                    senderPort = dataDatagram.getPort();

                    SrtpPacket dataPacket = SrtpPacket.fromBytes(Arrays.copyOf(dataDatagram.getData(), dataDatagram.getLength()));
                    
                    // Valida CRC (regras de erro)
                    if (dataPacket == null || dataPacket.isSyn() || (dataPacket.isAck() && dataPacket.isNack())) {
                        Log.writeLine("Pacote corrompido ou com CRC inválido recebido no lote.");
                        sendNack = true;
                        break; // Quebra o for para parar de ler a janela e enviar o NACK
                    }

                    // Se é pacote de finalização (FIN)
                    if (dataPacket.isFin()) {
                        Log.writeLine("FIN recebido. Push da aplicação com " + applicationBuffer.size() + " bytes.");
                        try (FileOutputStream outputStream = new FileOutputStream("received.txt")) {
                            outputStream.write(applicationBuffer.toByteArray());
                            outputStream.flush();
                        }
                        applicationBuffer.reset();

                        // Envia FIN+ACK para finalizar
                        SrtpPacket finAckPacket = PacketFactory.createFinAckPacket();
                        finAckPacket.calculateCrc32();
                        byte[] finAckBytes = finAckPacket.toBytes();
                        socket.send(new DatagramPacket(finAckBytes, finAckBytes.length, senderAddress, senderPort));
                        return; // Encerra o método de recepção
                    }
                    
                    int payloadLength = dataPacket.getLength();
                    if (dataDatagram.getLength() < HEADER_SIZE + payloadLength) {
                        Log.writeLine("Pacote de dados incompleto.");
                        sendNack = true;
                        break;
                    }

                    // REGRA DE OURO DO GBN: O pacote recebido tem que ser exatamente a sequência esperada
                    if (dataPacket.getSeq() == expectedSeq) {
                        byte[] payload = Arrays.copyOfRange(dataDatagram.getData(), HEADER_SIZE, HEADER_SIZE + payloadLength);
                        if (payloadLength > 0) {
                            applicationBuffer.write(payload);
                        }
                        
                        Log.writeLine("Pacote processado com sucesso seq=" + expectedSeq);
                        
                        // Atualiza a próxima sequência esperada e contabiliza o pacote
                        expectedSeq = (expectedSeq + 1) & SEQ_MASK;
                        packetsReceivedInBatch++;
                        
                    } else {
                        // Se for de outra sequência (antigo ou pulou número), está fora de ordem.
                        Log.writeLine("Pacote fora de ordem. Esperado: " + expectedSeq + ", Recebido: " + dataPacket.getSeq());
                        sendNack = true;
                        break; 
                    }

                } catch (SocketTimeoutException e) {
                    // Dar timeout no meio do "for" significa que o sender parou de enviar a rajada.
                    // Isso é normal se chegamos ao final do arquivo (o sender envia menos pacotes que a janela)
                    // ou se pacotes finais foram perdidos na rede. Quebramos o loop para confirmar o que já chegou.
                    break;
                }
            }

            // Envia ACK cumulativo ou NACK a partir do não reconhecido
            if (senderAddress != null) {
                if (sendNack) {
                    // Manda NACK explicitamente pedindo a sequência que deu erro/faltou
                    Log.writeLine("Lote rejeitado. Enviando NACK para solicitar a seq=" + expectedSeq);
                    SrtpPacket nackPacket = PacketFactory.createNackPacket(expectedSeq);
                    nackPacket.calculateCrc32();
                    byte[] nackBytes = nackPacket.toBytes();
                    socket.send(new DatagramPacket(nackBytes, nackBytes.length, senderAddress, senderPort));
                    
                } else if (packetsReceivedInBatch > 0) {
                    // Se leu pacotes com sucesso, manda o ACK cumulativo da última sequência lida corretamente
                    int ackSeqToSend = (expectedSeq - 1) & SEQ_MASK;
                    Log.writeLine("Lote concluído. Enviando ACK cumulativo para seq=" + ackSeqToSend);
                    
                    SrtpPacket ackPacket = PacketFactory.createAckPacket(ackSeqToSend);
                    ackPacket.calculateCrc32();
                    byte[] ackBytes = ackPacket.toBytes();
                    socket.send(new DatagramPacket(ackBytes, ackBytes.length, senderAddress, senderPort));
                    
                } else {
                    // Acordou da espera e todos os pacotes foram duplicados/ignorados
                    // Reenvia o ACK do último pacote conhecido para ajudar o sender a se localizar
                    int ackSeqToSend = (expectedSeq - 1) & SEQ_MASK;
                    SrtpPacket ackPacket = PacketFactory.createAckPacket(ackSeqToSend);
                    ackPacket.calculateCrc32();
                    byte[] ackBytes = ackPacket.toBytes();
                    socket.send(new DatagramPacket(ackBytes, ackBytes.length, senderAddress, senderPort));
                }
            }
        }
    }
}