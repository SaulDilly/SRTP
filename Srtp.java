public class Srtp {
    public static void main(String[] args) throws Exception {

        ParameterParser parser = new ParameterParser(args);
        
        if (!parser.isValid()) {
            printUsage();
            throw new IllegalArgumentException("Parâmetros inválidos.");
        }

        if (parser.isListener()) {
            execReceiver(parser);
        } else {
            execSender(parser);
        }

    }

    /*
     * Rotina de execução do sender, que estabelece a conexão e envia o arquivo
     */
    private static void execSender(ParameterParser parser) throws Exception {
        Log.initSender();
        try {
            // Estabele conexão na porta 6000 com o receiver
            ConnectionHandler ch = new ConnectionHandler();
            ch.establishConnection(parser.getHost(), parser.getPort(), 10);
            // Inicia envio a partir da porta 6000
            Thread sendThread = new Thread(new SendFileTask(ModeFactory.createSender(parser.getMode(), ch.getHandshakeLength()), 
                                                            parser.getHost(), 
                                                            parser.getPort(), 
                                                            parser.getFilePath()));
            sendThread.setName("sender-file-transfer");
            sendThread.start();
            // Inicia a thread de recebimento na porta 6001 para receber o arquivo resposta
            Thread receiveThread = new Thread(new ReceiveFileTask(ModeFactory.createReceiver(parser.getMode(), ch.getHandshakeLength()), 
                                                                  parser.getPort() + 1));
            receiveThread.setName("sender-receive-response");
            receiveThread.start();
            sendThread.join();
            // Finaliza a conexão
            ch.endConnection(parser.getHost(), parser.getPort());
            receiveThread.join();
        } finally {
            Log.close();
        }
    }

    /*
     * Rotina de execução do sender, escuta a conexão e recebe o arquivo
     */
    private static void execReceiver(ParameterParser parser) throws Exception {
        Log.initReceiver();
        try {
            // Estabele conexão na porta 6000 com o sender
            ConnectionHandler ch = new ConnectionHandler();
            String host = ch.listenConnection(parser.getPort(), 8);
            // Inicia a thread de recebimento na porta 6000 para receber o arquivo enviado
            Thread receiveThread = new Thread(new ReceiveFileTask(ModeFactory.createReceiver(parser.getMode(), ch.getHandshakeLength()), 
                                                                  parser.getPort()));
            receiveThread.setName("receiver-file-transfer");
            receiveThread.start();
            // Inicia envio a partir da porta 6001 para enviar o arquivo resposta
            Thread sendThread = new Thread(new SendFileTask(ModeFactory.createSender(parser.getMode(), ch.getHandshakeLength()), 
                                                            host, 
                                                            parser.getPort() + 1,
                                                             "envio_resposta.txt"));
            sendThread.setName("receiver-send-response");
            sendThread.start();
            sendThread.join();
            // Finaliza a conexão na porta 6001
            ch.endConnection(host, parser.getPort() + 1);
            receiveThread.join();
        } finally {
            Log.close();
        }
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  <executável> --listen --port 6000");
        System.out.println("  <executável> --host 192.168.1.10 --port 6000 --file arquivo.txt");
        System.out.println("  <executável> --host 192.168.1.10 --port 6000 --file arquivo.txt --mode [saw|gbn|sr]");
    }
}
