public class SendFileTask implements Runnable {
    private SenderInterface sender;
    private String filePath;
    private int port;
    private String host;

    public SendFileTask(SenderInterface sender, String host, int port, String filePath) {
        this.sender = sender;
        this.host = host;
        this.filePath = filePath;
        this.port = port;
    }

    @Override
    public void run() {
        try {
            Log.writeLine("START SEND THREAD: host: " + host + ", port: " + port);
            sender.sendFile(host, port, filePath);
            Log.writeLine("FINISH SEND THREAD: host: " + host + ", port: " + port);
        } catch (Exception e) {
            System.err.println("Erro ao enviar o arquivo: " + e.getMessage());
        }
    }
    
}
