import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

public class ConnectionHandler {

    private static final int TIMEOUT = 100;
    private static final int MAX_PAYLOAD = 255;
    private static final int HEADER_SIZE = 9;

    private int length;

    public ConnectionHandler() {
        length = 0;
    }

    /*
     * Estabelece a conexão com o host especificado, realizando o handshake de três vias (SYN, SYN+ACK, ACK)
     */ 
    public void establishConnection(String host, int port, int lengthHandshake) {
        Log.writeLine("Iniciando conexão...");
        // Monta o pacote SYN e calcula o CRC32 antes de enviar
        SrtpPacket synPacket = PacketFactory.createSynPacket(0);
        synPacket.setLength(lengthHandshake);
        synPacket.calculateCrc32();
        byte[] packetBytes = synPacket.toBytes();

        try (DatagramSocket socket = new DatagramSocket(port)) {
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
                        // Acorda length negociado com o receiver, que pode ser menor que o máximo permitido
                        this.length = Math.min(lengthHandshake, responsePacket.getLength());
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
     * Encerra a conexão, enviando FIN e aguardando FIN+ACK
     */
    public void endConnection(String host, int port) {
        Log.writeLine("Encerrando conexão...");
        // Monta o pacote FIN e calcula o CRC32 antes de enviar
        SrtpPacket finPacket = PacketFactory.createFinPacket();
        finPacket.calculateCrc32();
        byte[] packetBytes = finPacket.toBytes();

        try (DatagramSocket socket = new DatagramSocket(port)) {
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

    /*
     * Escuta conexões de entrada na porta especificada, realizando o handshake de três vias (SYN, SYN+ACK, ACK)
     */ 
    public String listenConnection(int port, int lengthHandshake) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(TIMEOUT);
            byte[] receiveBuffer = new byte[HEADER_SIZE + MAX_PAYLOAD];

            while (true) {
                // Escuta pacote de SYN
                DatagramPacket incoming = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                Log.writeLine("Aguardando conexão do sender...");
                socket.receive(incoming);
                
                // Se estiver inválido ou não for um SYN, ignora e continua escutando
                SrtpPacket synPacket = SrtpPacket.fromBytes(Arrays.copyOf(incoming.getData(), incoming.getLength()));
                if (synPacket == null || !synPacket.isSyn()) {
                    Log.writeLine("Não recebi um pacote válido.");
                    continue;
                }

                // Envia já o mínimo entre os dois
                this.length = Math.min(lengthHandshake, synPacket.getLength());

                // Cria o pacote SYN+ACK, calcula o CRC32 e monta o datagrama para resposta
                SrtpPacket synAckPacket = PacketFactory.createSynAckPacket(MAX_PAYLOAD);
                synAckPacket.setLength(length);
                synAckPacket.calculateCrc32();
                byte[] synAckBytes = synAckPacket.toBytes();
                // Determina o endereço de envio do SYN+ACK com base no pacote recebido
                DatagramPacket synAckDatagram = new DatagramPacket(
                        synAckBytes,
                        synAckBytes.length,
                        incoming.getAddress(),
                        incoming.getPort());

                // Estado intermediário: aguardando ACK final ou o início da transmissão
                while (true) {
                    // Realiza o envio do SYN+ACK
                    Log.writeLine("Enviando pacote SYN+ACK...");
                    socket.send(synAckDatagram);

                    // Aguarda o ACK do sender
                    try {
                        DatagramPacket ackDatagram = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                        Log.writeLine("Aguardando pacote ACK...");
                        socket.receive(ackDatagram);

                        // Trata o pacote recebido
                        SrtpPacket ackPacket = SrtpPacket.fromBytes(Arrays.copyOf(ackDatagram.getData(), ackDatagram.getLength()));
                        if (ackPacket != null && ackPacket.isAck() && !ackPacket.isSyn()) {
                            Log.writeLine("Recebi um pacote válido de ACK.");
                            return incoming.getAddress().getHostAddress();
                        }
                        
                        // Se é pacote válido e é o início da transmissão, considera a conexão estabelecida e parte para o tratamento
                        if (isDataStartPacket(ackPacket)) {
                            Log.writeLine("Recebi um pacote inicial de transmissão, considerando como ACK OK.");
                            return incoming.getAddress().getHostAddress();
                        }
                    } catch (SocketTimeoutException timeoutException) {
                        Log.writeLine("Ocorreu timeout.");
                    }
                }
            }
        } catch (Exception exception) {
            throw new RuntimeException("Falha ao receber a conexão via UDP", exception);
        }
    }

    /* 
     * Verifica se o pacote recebido é um pacote de início de transmissão, ou seja, não é SYN, ACK ou NACK
     */
    private boolean isDataStartPacket(SrtpPacket packet) {
        return packet != null
                && !packet.isSyn()
                && !packet.isAck()
                && !packet.isNack();
    }

    /* 
     * Retorna tamanho da janela negociada durante o handshake, ou 0 se o handshake não foi realizado
     */
    public int getHandshakeLength() {
        return length;
    }
}
