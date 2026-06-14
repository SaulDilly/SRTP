public class ParameterParser {
    private boolean listener;
    private String host;
    private int port;
    private String filePath;

    public ParameterParser(String[] args) {
        this.listener = false;
        this.host = null;
        this.port = -1;
        this.filePath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i].toLowerCase()) {
                case "--listen":
                    this.listener = true;
                    break;
                case "--host":
                    if (i + 1 < args.length) {
                        this.host = args[++i];
                    }
                    break;
                case "--port":
                    if (i + 1 < args.length) {
                        this.port = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--file":
                    if (i + 1 < args.length) {
                        this.filePath = args[++i];
                    }
                    break;
            }
        }
    }

    public boolean isListener() {
        return listener;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
    
    public String getFilePath() {
        return filePath;
    }

    public boolean isValid() {
        if (listener) {
            return port > 0;
        } else {
            return host != null && port > 0 && filePath != null;
        }
    }
}
