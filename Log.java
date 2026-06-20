import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Log {
    private static BufferedWriter writer;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void initSender() throws IOException {
        initialize("LogSender.txt");
    }

    public static void initReceiver() throws IOException {
        initialize("LogReceiver.txt");
    }

    public static synchronized void writeLine(String line) {
        if (writer == null) {
            throw new IllegalStateException("Log nao foi inicializado.");
        }

        try {
            writer.write(formatLine(line));
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            throw new RuntimeException("Falha ao escrever no log", exception);
        }
    }

    public static synchronized void close() {
        if (writer == null) {
            return;
        }

        try {
            writer.close();
            writer = null;
        } catch (IOException exception) {
            throw new RuntimeException("Falha ao fechar o log", exception);
        }
    }

    private static synchronized void initialize(String fileName) throws IOException {
        close();
        writer = new BufferedWriter(new FileWriter(fileName, false));
    }

    private static String formatLine(String line) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String threadName = Thread.currentThread().getName();
        return "[" + timestamp + "] [" + threadName + "] " + line;
    }
}