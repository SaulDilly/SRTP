import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class GBNSender implements SenderInterface {
    
    private static final int SEQ_MASK = 0x3FFF;
    private static final int TIMEOUT = 110;
    private int seq;
    private SrtpPacket[] window;
    private byte[][] windowData;

    public GBNSender(int windowLength) {
        this.seq = 0;
        this.window = new SrtpPacket[windowLength];
        this.windowData = new byte[windowLength][];
    }

    /*
     * Envia os pacotes dentro da janela de envio, e aguarda os ACKs para cada pacote
     * Em caso de timeout, reenvia todos os pacotes da janela de envio
     */
    public void sendFile(String host, int port, String filePath) {
        if (host.isEmpty() || port <= 0) {
            throw new IllegalStateException("Parâmetros de conexão inválidos.");
        }

        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("Caminho do arquivo inválido.");
        }

        long startTimeNanos = System.nanoTime();

        try (DatagramSocket socket = new DatagramSocket(port);
             FileInputStream inputStream = new FileInputStream(filePath)) {

            socket.setSoTimeout(TIMEOUT);
            InetAddress address = InetAddress.getByName(host);
            // Payload pode ter no máximo 255 bytes, então o buffer de leitura é desse tamanho
            byte[] buffer = new byte[255];
            // O receiver enviará apenas o cabeçalho de volta no ACK, então o buffer de recepção é do tamanho do cabeçalho (9 bytes)
            byte[] receiveBuffer = new byte[9];

            // Indica final de arquivo
            boolean eof = false;
            // Número de pacotes atualmente na janela de envio
            int packetsInWindow = 0;

            while (!eof || packetsInWindow > 0) {

                // Primeiro preenche a janela de envio com os pacotes seguintes do arquivo, até o limite da janela ou do arquivo
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

                    // Armazena na janela (no primeiro espaço nulo)
                    window[packetsInWindow] = packet;
                    windowData[packetsInWindow] = packetBytes;
                    packetsInWindow++;

                    // Incrementa a sequência aplicando a máscara (wrap-around)
                    seq = (seq + 1) & SEQ_MASK; 
                }

                boolean enviarJanela = true;

                // Envia a janela, com proteção de retransmissão
                while (true) {
                    if (enviarJanela) {
                        for (int i = 0; i < packetsInWindow; i++) {
                            if (windowData[i] != null) {
                                Log.writeLine("Enviando pacote seq=" + window[i].getSeq());
                                socket.send(new DatagramPacket(windowData[i], windowData[i].length, address, port));
                            }
                        }
                        enviarJanela = false; // Evita reenvio imediato da janela
                    }

                    // Espera ACK do envio
                    try {
                        DatagramPacket ackDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                        socket.receive(ackDatagram);
                        
                        SrtpPacket responsePacket = SrtpPacket.fromBytes(Arrays.copyOf(ackDatagram.getData(), ackDatagram.getLength()));
                        
                        // Se estiver corrompido, realiza o envio inteiro da janela novamente
                        if (responsePacket == null || responsePacket.isSyn() || (!responsePacket.isAck() && !responsePacket.isNack())) {
                            Log.writeLine("ACK com CRC inválido. Reenviando janela.");
                            continue;
                        }

                        int nextSeq = 0;
                        // Se chegou NACK, o próximo a enviar é a sequência indicadada no pacote
                        if (responsePacket.isNack()) {
                            nextSeq = responsePacket.getAckSeq() & SEQ_MASK;
                            Log.writeLine("NACK recebido para a seq=" + nextSeq);
                        } else {
                            // Se chegou ACK, o próximo a enviar é a sequência seguinte ao ackada
                            nextSeq = (responsePacket.getAckSeq() + 1) & SEQ_MASK;
                            Log.writeLine("ACK recebido para seq=" + responsePacket.getAckSeq());
                        }
                        
                        // Calcula quantos pacotes da janela foram confirmados
                        int baseSeq = window[0].getSeq();
                        int ackedCount = (nextSeq - baseSeq) & SEQ_MASK;

                        // Valida se o ACK faz sentido para a janela atual
                        if (ackedCount > 0 && ackedCount <= packetsInWindow) {
                            Log.writeLine(ackedCount + " pacote(s) confirmado(s). Deslizando a janela.");
                            
                            // Move os pacotes restantes para o início da janela
                            for (int i = 0; i < packetsInWindow - ackedCount; i++) {
                                window[i] = window[i + ackedCount];
                                windowData[i] = windowData[i + ackedCount];
                            }
                            
                            // Limpa os espaços que ficaram livres no final
                            for (int i = packetsInWindow - ackedCount; i < packetsInWindow; i++) {
                                window[i] = null;
                                windowData[i] = null;
                            }
                            
                            // Atualiza a quantidade de pacotes e sai do loop de reenvio
                            packetsInWindow -= ackedCount;
                            break; 

                        } else if (responsePacket.isNack() && ackedCount == 0) {
                            // O NACK recebido é para o primeiro pacote da janela, ou seja, o próximo a enviar. Reenvia a janela imediatamente.
                            Log.writeLine("NACK recebido para o primeiro pacote da janela. Reenviando janela.");
                            enviarJanela = true; // Sinaliza para reenviar a janela no próximo loop

                        }
                        else if (ackedCount > packetsInWindow) {
                            // Um ACK fora do esperado. Pode ser um pacote duplicado atrasado na rede.
                            Log.writeLine("ACK ignorado (fora da janela de envio). Aguardando timeout...");
                            // O loop continuará aguardando (podendo dar timeout e reenviar a janela)
                        }
                    } catch (SocketTimeoutException e) {
                        Log.writeLine("Ocorreu timeout no envio da janela. Será reenviada.");
                        enviarJanela = true;
                    }
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
}
