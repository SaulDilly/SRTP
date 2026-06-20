public class ReceiveFileTask implements Runnable {
    private SAWReceiver receiver;
    private int port;

    public ReceiveFileTask(SAWReceiver receiver, int port) {
        this.receiver = receiver;
        this.port = port;
    }

    @Override
    public void run() {
        try {
            receiver.receiveFile(port);
        } catch (Exception e) {
            System.err.println("Erro ao receber o arquivo: " + e.getMessage());
        }
    }
    
}
