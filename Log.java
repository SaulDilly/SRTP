import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public final class Log {
    private static BufferedWriter writer;

    private Log() {
    }

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
            writer.write(line);
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
        writer = new BufferedWriter(new FileWriter(fileName, true));
    }
}