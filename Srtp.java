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
            SAWSender sender = new SAWSender();
            sender.establishConnection(parser.getHost(), parser.getPort());
            sender.sendFile(parser.getFilePath());
            sender.endConnection();
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
            SAWReceiver receiver = new SAWReceiver();
            receiver.listenConnection(parser.getPort());
            receiver.receiveFile();
        } finally {
            Log.close();
        }
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  <executável> --listen --port 6000");
        System.out.println("  <executável> --host 192.168.1.10 --port 6000 --file arquivo.bin");
    }
}
