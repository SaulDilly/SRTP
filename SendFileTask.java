public class SendFileTask implements Runnable {
    private SAWSender sender;
    private String filePath;
    private int port;
    private String host;

    public SendFileTask(SAWSender sender, String host, int port, String filePath) {
        this.sender = sender;
        this.host = host;
        this.filePath = filePath;
        this.port = port;
    }

    @Override
    public void run() {
        try {
            sender.sendFile(host, port, filePath);
        } catch (Exception e) {
            System.err.println("Erro ao enviar o arquivo: " + e.getMessage());
        }
    }
    
}
