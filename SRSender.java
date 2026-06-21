import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class SRSender implements SenderInterface {
    
    private static final int SEQ_MASK = 0x3FFF;
    private static final int TIMEOUT = 100; // Tempo em milissegundos
    private int seq;
    
    // Arrays paralelos que representam o estado da janela
    private SrtpPacket[] window;
    private byte[][] windowData;
    private boolean[] acked;      // true se o pacote recebeu ACK individual

    public SRSender(int windowLength) {
        this.seq = 0;
        this.window = new SrtpPacket[windowLength];
        this.windowData = new byte[windowLength][];
        this.acked = new boolean[windowLength];
    }

    public void sendFile(String host, int port, String filePath) {
        if (host.isEmpty() || port <= 0) {
            throw new IllegalStateException("Parâmetros inválidos.");
        }
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("Caminho inválido.");
        }

        long startTimeNanos = System.nanoTime();

        try (DatagramSocket socket = new DatagramSocket(port); 

            FileInputStream inputStream = new FileInputStream(filePath)) {
            InetAddress address = InetAddress.getByName(host);
            // Payload pode ter no máximo 255 bytes, então o buffer de leitura é desse tamanho
            byte[] buffer = new byte[255];
            // O receiver enviará apenas o cabeçalho de volta no ACK, então o buffer de recepção é do tamanho do cabeçalho (9 bytes)
            byte[] receiveBuffer = new byte[9];
            
            boolean eof = false;
            int packetsInWindow = 0;

            while (!eof || packetsInWindow > 0) {

                // Preenche a janela, lendo dados novos apenas dados novos nos espaços 
                while (packetsInWindow < window.length && !eof) {
                    int read = inputStream.read(buffer);
                    
                    if (read == -1) {
                        eof = true;
                        break;
                    }

                    byte[] payload = Arrays.copyOf(buffer, read);
                    SrtpPacket packet = new SrtpPacket(seq);
                    packet.setLength(read);
                    packet.calculateCrc32(payload);
                    
                    byte[] header = packet.toBytes();
                    byte[] packetBytes = new byte[header.length + payload.length];
                    System.arraycopy(header, 0, packetBytes, 0, header.length);
                    System.arraycopy(payload, 0, packetBytes, header.length, payload.length);

                    // Adiciona os dados na primeira posição livre do final da janela
                    window[packetsInWindow] = packet;
                    windowData[packetsInWindow] = packetBytes;
                    acked[packetsInWindow] = false; // Pacote novo, ainda não confirmado

                    seq = (seq + 1) & SEQ_MASK;
                    packetsInWindow++;
                }

                // Envia os pacotes da janela
                for (int i = 0; i < packetsInWindow; i++) {
                    if (!acked[i]) {
                        Log.writeLine("Enviando pacote seq=" + window[i].getSeq());
                        socket.send(new DatagramPacket(windowData[i], windowData[i].length, address, port));
                    }
                }

                // Processa as respostas 
                while (true) {

                    // Se todos os pacotes da janela já receberam ACK individual, não é necessário esperar por mais respostas e o loop pode ser interrompido para avançar a janela
                    boolean allAcked = true;
                    for (int i = 0; i < packetsInWindow; i++) {
                        if (!acked[i]) {
                            allAcked = false;
                            break;
                        }
                    }
                    // Se todos já estão ackados, sai do laço sem esperar o timeout
                    if (allAcked) {
                        Log.writeLine("Todos os pacotes da janela confirmados. Avançando imediatamente.");
                        break;
                    }

                    // Recebe e processa as respostas individuais (ACKs e NACKs) para cada pacote da janela
                    try {
                        socket.setSoTimeout(TIMEOUT);
                        
                        DatagramPacket ackDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                        socket.receive(ackDatagram);
                        
                        SrtpPacket responsePacket = SrtpPacket.fromBytes(Arrays.copyOf(ackDatagram.getData(), ackDatagram.getLength()));
                        
                        if (responsePacket != null && (responsePacket.isAck() || responsePacket.isNack()) && !responsePacket.isSyn()) {
                            int ackSeq = responsePacket.getAckSeq();
                            int index = findIndexInWindow(ackSeq, packetsInWindow);
                            
                            // Garante que a resposta pertence a um pacote que está atualmente na janela de envio
                            if (index != -1) {
                                if (responsePacket.isAck()) {
                                    if (!acked[index]) {
                                        Log.writeLine("ACK individual recebido para seq=" + ackSeq);
                                        // Marca pacote como reconhecido
                                        acked[index] = true; 
                                    }
                                } else if (responsePacket.isNack()) {
                                    // Faz o reenvio isolado estando no loop do receive
                                    Log.writeLine("NACK recebido para seq=" + ackSeq + ". Reenviando isoladamente.");
                                    socket.send(new DatagramPacket(windowData[index], windowData[index].length, address, port));
                                }
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        // Se ocorrer timeout, apenas os pacotes que não receberam ACK individual serão retransmitidos (isoladamente)
                        break;
                    }
                }

                // No SR, a janela SÓ desliza se o primeiro pacote (a base) foi confirmado.
                int shiftCount = 0;
                while (shiftCount < packetsInWindow && acked[shiftCount]) {
                    shiftCount++;
                }

                if (shiftCount > 0) {
                    Log.writeLine("Deslizando a base da janela em " + shiftCount + " pacote(s).");
                    
                    // Puxa os pacotes não confirmados (e os confirmados fora de ordem) para o início
                    for (int i = 0; i < packetsInWindow - shiftCount; i++) {
                        window[i] = window[i + shiftCount];
                        windowData[i] = windowData[i + shiftCount];
                        acked[i] = acked[i + shiftCount];
                    }
                    
                    // Limpa o rastro no final dos arrays
                    for (int i = packetsInWindow - shiftCount; i < packetsInWindow; i++) {
                        window[i] = null;
                        windowData[i] = null;
                        acked[i] = false;
                    }
                    
                    packetsInWindow -= shiftCount;
                }
            }
        } catch (IOException exception) {
            throw new RuntimeException("Falha ao ler o arquivo para envio", exception);
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao enviar o arquivo", exception);
        } finally {
            long elapsedMillis = (System.nanoTime() - startTimeNanos) / 1_000_000L;
            Log.writeLine("Envio concluído em " + elapsedMillis + " ms.");
        }
    }

    /*
     * Mapeia um número de sequência (que pode ter sofrido wrap-around)
     * para o seu respectivo índice no array da janela atual.
     */
    private int findIndexInWindow(int targetSeq, int packetsInWindow) {
        if (packetsInWindow == 0) return -1;
        
        int baseSeq = window[0].getSeq();
        // Cálculo seguro de distância considerando estouro numérico (wrap-around)
        int dist = (targetSeq - baseSeq) & SEQ_MASK;
        
        // Se a distância é menor que os pacotes ativos na janela, o pacote pertence à janela
        if (dist >= 0 && dist < packetsInWindow) {
            return dist;
        }
        return -1;
    }
}