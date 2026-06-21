public class ModeFactory {
    public static SenderInterface createSender(String mode, int windowLength) {
        switch (mode.toLowerCase()) {
            case "gbn":
                return new GBNSender(windowLength);
            default:
                return new SAWSender();
        }
    }

    public static ReceiverInterface createReceiver(String mode, int windowLength) {
        switch (mode.toLowerCase()) {
            case "gbn":
                return new GBNReceiver(windowLength);
            default:
                return new SAWReceiver();
        }
    }    
}
