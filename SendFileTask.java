public class SendFileTask implements Runnable {
    private SAWSender sender;
    private String filePath;
    private int port;

    public SendFileTask(SAWSender sender, int port, String filePath) {
        this.sender = sender;
        this.filePath = filePath;
        this.port = port;
    }

    @Override
    public void run() {
        try {
            sender.sendFile(port, filePath);
        } catch (Exception e) {
            System.err.println("Erro ao enviar o arquivo: " + e.getMessage());
        }
    }
    
}
