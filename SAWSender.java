import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class SAWSender {
    private static final int SEQ_MASK = 0x3FFF;
    private int seq;
    private static final int TIMEOUT = 100;

    public SAWSender() {
        this.seq = 0;
    }

    /*
     * Estabelece a conexão com o host especificado, realizando o handshake de três vias (SYN, SYN+ACK, ACK)
     */ 
    public void establishConnection(String host, int port) {
        Log.writeLine("Iniciando conexão...");
        // Monta o pacote SYN e calcula o CRC32 antes de enviar
        SrtpPacket synPacket = PacketFactory.createSynPacket(0);
        synPacket.calculateCrc32();
        byte[] packetBytes = synPacket.toBytes();

        try (DatagramSocket socket = new DatagramSocket()) {
            // Monta o datagrama com base no pacote convertido para bytes
            socket.setSoTimeout(TIMEOUT);
            InetAddress address = InetAddress.getByName(host);
            DatagramPacket datagram = new DatagramPacket(packetBytes, packetBytes.length, address, port);
            // Cabeçalho tem 9 bytes
            byte[] receiveBuffer = new byte[9];

            while (true) {
                // Envia SYN
                Log.writeLine("Enviando pacote SYN...");
                socket.send(datagram);

                try {
                    // Aguarda o SYN+ACK
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    Log.writeLine("Aguardando pacote SYN+ACK...");

                    socket.receive(response);

                    SrtpPacket responsePacket = SrtpPacket.fromBytes(Arrays.copyOf(response.getData(), response.getLength()));
                    // Se é um pacote válido e é um SYN+ACK, sai do loop
                    if (responsePacket != null && responsePacket.isSyn() && responsePacket.isAck()) {
                        break;
                    }
                    Log.writeLine("Não recebi um pacote válido.");
                } catch (SocketTimeoutException timeoutException) {
                    Log.writeLine("Ocorreu timeout ao aguardar o SYN+ACK.");
                }
            }

            // Envia o ACK final para completar o handshake
            SrtpPacket ackPacket = PacketFactory.createAckPacket(0);
            ackPacket.calculateCrc32();
            byte[] ackBytes = ackPacket.toBytes();
            DatagramPacket ackDatagram = new DatagramPacket(ackBytes, ackBytes.length, address, port);
            socket.send(ackDatagram);
            Log.writeLine("Enviando pacote ACK final para completar o handshake...");
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao enviar o pacote SYN via UDP", exception);
        }
    }

    /*
     * Envia arquivo, pacote a pacote
     * Envia, aguarda o ACK para cada pacote, e em caso de timeout, reenvia o pacote até receber o ACK correspondente
     */ 
    public void sendFile(String host, int port, String filePath) {
        if (host.isEmpty() || port <= 0) {
            throw new IllegalStateException("Conexão não estabelecida. Chame establishConnection() antes de enviar o arquivo.");
        }

        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("Caminho do arquivo inválido.");
        }

        long startTimeNanos = System.nanoTime();
        boolean sentAnyData = false;
        boolean lastChunkWasFull = false;

        try (DatagramSocket socket = new DatagramSocket();
             FileInputStream inputStream = new FileInputStream(filePath)) {

            socket.setSoTimeout(TIMEOUT);
            InetAddress address = InetAddress.getByName(host);
            // Payload pode ter no máximo 255 bytes, então o buffer de leitura é desse tamanho
            byte[] buffer = new byte[255];
            // O receiver enviará apenas o cabeçalho de volta no ACK, então o buffer de recepção é do tamanho do cabeçalho (9 bytes)
            byte[] receiveBuffer = new byte[9];

            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                sentAnyData = true;
                lastChunkWasFull = read == buffer.length;

                // Payload = próximos N bytes do arquivo
                byte[] payload = Arrays.copyOf(buffer, read);

                // Loop para enviar o pacote formado até receber o ack
                int currentSeq = seq;
                while (true) {
                    // Instancia o pacote de dados da sequência atual, com o tamanho lido
                    SrtpPacket packet = new SrtpPacket(currentSeq);
                    packet.setLength(read);
                    // Calcula e armazena o CRC32 do pacote, considerando o cabeçalho e o payload
                    packet.calculateCrc32(payload);
                    byte[] header = packet.toBytes();
                    byte[] packetBytes = new byte[header.length + payload.length];

                    // Concatena o cabeçalho com o payload para formar o pacote completo a ser enviado
                    System.arraycopy(header, 0, packetBytes, 0, header.length);
                    System.arraycopy(payload, 0, packetBytes, header.length, payload.length);

                    Log.writeLine("Enviando pacote de dados seq=" + seq + " tamanho=" + read);
                    socket.send(new DatagramPacket(packetBytes, packetBytes.length, address, port));

                    try {
                        DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                        Log.writeLine("Aguardando ACK do seq=" + seq);
                        socket.receive(response);

                        SrtpPacket responsePacket = SrtpPacket.fromBytes(Arrays.copyOf(response.getData(), response.getLength()));
                        if (isAckForSeq(responsePacket, currentSeq)) {
                            Log.writeLine("ACK recebido para seq=" + currentSeq);
                            break;
                        }

                        if (responsePacket != null && responsePacket.isNack()) {
                            currentSeq = responsePacket.getAckSeq() & SEQ_MASK;
                            seq = currentSeq;
                            Log.writeLine("NACK recebido. Reenviando a partir da seq=" + currentSeq);
                            continue;
                        }
                    } catch (SocketTimeoutException timeoutException) {
                        Log.writeLine("Timeout ao aguardar ACK do seq=" + currentSeq + ", reenviando pacote.");
                    }
                }
                seq = (currentSeq + 1) & SEQ_MASK;
            }

            // Se não enviou dados, ou se o último pacote enviado foi um pacote cheio (arquivo múltiplo de 255 bytes)
            if (!sentAnyData || lastChunkWasFull) {
                SrtpPacket endPacket = new SrtpPacket(seq);
                endPacket.setLength(0);
                endPacket.calculateCrc32();
                byte[] endBytes = endPacket.toBytes();

                // Envia pacote com tamanho 0 para sinalizar o fim do stream, e aguarda o ACK desse pacote de finalização, reenvia o pacote de finalização em caso de timeout, até receber o ACK correspondente
                while (true) {
                    Log.writeLine("Enviando pacote de finalização com length=0.");
                    socket.send(new DatagramPacket(endBytes, endBytes.length, address, port));

                    try {
                        DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                        Log.writeLine("Aguardando ACK do pacote de finalização seq=" + seq);
                        socket.receive(response);

                        SrtpPacket ackPacket = SrtpPacket.fromBytes(Arrays.copyOf(response.getData(), response.getLength()));
                        if (isAckForSeq(ackPacket, seq)) {
                            Log.writeLine("ACK recebido para o pacote de finalização seq=" + seq);
                            break;
                        }
                    } catch (SocketTimeoutException timeoutException) {
                        Log.writeLine("Timeout ao aguardar ACK do pacote de finalização seq=" + seq + ", reenviando pacote.");
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

    private boolean isAckForSeq(SrtpPacket packet, int expectedSeq) {
        return packet != null && packet.isAck() && packet.getAckSeq() == (expectedSeq & SEQ_MASK);
    }

    /*
     * Encerra a conexão, enviando FIN e aguardando FIN+ACK
     */
    public void endConnection(String host,int port) {
        Log.writeLine("Encerrando conexão...");
        // Monta o pacote FIN e calcula o CRC32 antes de enviar
        SrtpPacket finPacket = PacketFactory.createFinPacket();
        finPacket.calculateCrc32();
        byte[] packetBytes = finPacket.toBytes();

        try (DatagramSocket socket = new DatagramSocket()) {
            // Monta o datagrama com base no pacote convertido para bytes
            socket.setSoTimeout(TIMEOUT);
            InetAddress address = InetAddress.getByName(host);
            DatagramPacket datagram = new DatagramPacket(packetBytes, packetBytes.length, address, port);
            // Cabeçalho tem 9 bytes
            byte[] receiveBuffer = new byte[9];

            while (true) {
                // Envia FIN
                Log.writeLine("Enviando pacote FIN...");
                socket.send(datagram);

                try {
                    // Aguarda o FIN+ACK
                    DatagramPacket response = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    Log.writeLine("Aguardando pacote FIN+ACK...");

                    socket.receive(response);

                    SrtpPacket responsePacket = SrtpPacket.fromBytes(Arrays.copyOf(response.getData(), response.getLength()));
                    // Se é um pacote válido e é um FIN+ACK, sai do loop
                    if (responsePacket != null && responsePacket.isFin() && responsePacket.isAck()) {
                        break;
                    }
                    Log.writeLine("Não recebi um pacote válido.");
                } catch (SocketTimeoutException timeoutException) {
                    Log.writeLine("Ocorreu timeout ao aguardar o FIN+ACK.");
                }
            }
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao enviar o pacote FIN via UDP", exception);
        }

    }
    
}
