public class ReceiveFileTask implements Runnable {
    private ReceiverInterface receiver;
    private int port;

    public ReceiveFileTask(ReceiverInterface receiver, int port) {
        this.receiver = receiver;
        this.port = port;
    }

    @Override
    public void run() {
        try {
            Log.writeLine("START RECEIVE THREAD: port: " + port);
            receiver.receiveFile(port);
            Log.writeLine("FINISH RECEIVE THREAD: port: " + port);
        } catch (Exception e) {
            System.err.println("Erro ao receber o arquivo: " + e.getMessage());
        }
    }
    
}
