public class ModeFactory {
    public static SenderInterface createSender(String mode, int windowLength) {
        if (mode == null) {
            return new SAWSender();
        }
        switch (mode.toLowerCase()) {
            case "gbn":
                return new GBNSender(windowLength);
            case "sr":
                return new SRSender(windowLength);
            default:
                return new SAWSender();
        }
    }

    public static ReceiverInterface createReceiver(String mode, int windowLength) {
        if (mode == null) {
            return new SAWReceiver();
        }
        switch (mode.toLowerCase()) {
            case "gbn":
                return new GBNReceiver(windowLength);
            case "sr":
                return new SRReceiver(windowLength);
            default:
                return new SAWReceiver();
        }
    }    
}
