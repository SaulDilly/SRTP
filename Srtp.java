public class Srtp {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        if ("--listen".equalsIgnoreCase(args[0])) {
            int port = parsePort(args, "--port");
            SAWReceiver receiver = new SAWReceiver();
            receiver.listenConnection(port);
            return;
        }

        String host = parseValue(args, "--host");
        int port = parsePort(args, "--port");
        String filePath = parseValue(args, "--file");

        if (host == null || filePath == null) {
            printUsage();
            throw new IllegalArgumentException("Modo sender requer --host, --port e --file.");
        }

        // O filePath foi interpretado e fica disponível para o próximo passo da transmissão.
        SAWSender sender = new SAWSender();
        sender.establishConnection(host, port);
    }

    private static String parseValue(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equalsIgnoreCase(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static int parsePort(String[] args, String flag) {
        String value = parseValue(args, flag);
        if (value == null) {
            throw new IllegalArgumentException("Parâmetro obrigatório ausente: " + flag);
        }
        return Integer.parseInt(value);
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  <executável> --listen --port 6000");
        System.out.println("  <executável> --host 192.168.1.10 --port 6000 --file arquivo.bin");
    }
}
