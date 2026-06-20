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
            SAWSender sender = new SAWSender();
            sender.establishConnection(parser.getHost(), parser.getPort());
            // Inicia envio a partir da porta 6000
            Thread sendThread = new Thread(new SendFileTask(sender, parser.getHost(), parser.getPort(), parser.getFilePath()));
            sendThread.start();
            // Inicia a thread de recebimento na porta 6001 para receber o arquivo resposta
            SAWReceiver receiver = new SAWReceiver();
            Thread receiveThread = new Thread(new ReceiveFileTask(receiver, parser.getPort() + 1));
            receiveThread.start();
            sendThread.join();
            receiveThread.join();
            // Finaliza a conexão
            sender.endConnection(parser.getHost(), parser.getPort());
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
            SAWReceiver receiver = new SAWReceiver();
            String host = receiver.listenConnection(parser.getPort());
            // Inicia a thread de recebimento na porta 6000 para receber o arquivo enviado
            Thread receiveThread = new Thread(new ReceiveFileTask(receiver, parser.getPort()));
            receiveThread.start();
            // Inicia envio a partir da porta 6001 para enviar o arquivo resposta
            SAWSender sender = new SAWSender();
            Thread sendThread = new Thread(new SendFileTask(sender, host, parser.getPort() + 1, "envio_resposta.txt"));
            sendThread.start();
            receiveThread.join();
            sendThread.join();
            // Finaliza a conexão
            sender.endConnection(host, parser.getPort() + 1);
        } finally {
            Log.close();
        }
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  <executável> --listen --port 6000");
        System.out.println("  <executável> --host 192.168.1.10 --port 6000 --file arquivo.txt");
    }
}
