package servidor;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import protocolos.*;

public class ServidorEmparejamiento {
    private final int puertoControl = 5000;
    private ServerSocket serverSocket;
    private Semaphore semaforoIdGrupo = new Semaphore(1);
    private int siguienteIdGrupo = 0;
    private final int MAX_GRUPOS = 3;
    private List<Socket> esperaClientes = Collections.synchronizedList(new ArrayList<>());

    // Rangos multicast válidos para grupos
    private List<String> ipsMulticast = Arrays.asList(
            "239.0.0.1", "239.0.0.2", "239.0.0.3"
    );
    private int puertoMulticastBase = 6000;

    public ServidorEmparejamiento() throws IOException {
        serverSocket = new ServerSocket(puertoControl);
        log("[SERVIDOR] Servidor iniciado en puerto " + puertoControl);
    }

    public void start() throws IOException {
        log("[SERVIDOR] Esperando clientes...");
        while(true) {
            Socket cliente = serverSocket.accept();
            log("[SERVIDOR] Cliente conectado desde: " + cliente.getInetAddress());

            new Thread(() -> {
                try {
                    ObjectInputStream ois = new ObjectInputStream(cliente.getInputStream());
                    SolicitudConexion solicitud = (SolicitudConexion) ois.readObject();
                    log("[SERVIDOR] Solicitud de conexión de cliente: " + solicitud.idCliente);

                    synchronized (esperaClientes) {
                        esperaClientes.add(cliente);
                        log("[SERVIDOR] Clientes en espera: " + esperaClientes.size());

                        if(esperaClientes.size() >= 4) {
                            formarGrupo(4);
                        }
                    }
                } catch (Exception e) {
                    log("[SERVIDOR ERROR] " + e.getMessage());
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private void formarGrupo(int tamGrupo) {
        try {
            semaforoIdGrupo.acquire();
            int idGrupo = siguienteIdGrupo++;
            semaforoIdGrupo.release();

            String ipMulticast = ipsMulticast.get(idGrupo % ipsMulticast.size());
            int puerto = puertoMulticastBase + idGrupo;

            List<Socket> grupoClientes = new ArrayList<>();
            for (int i=0; i < tamGrupo; i++) {
                Socket cliente = esperaClientes.remove(0);
                grupoClientes.add(cliente);
                log("[SERVIDOR] Cliente removido de espera para grupo " + idGrupo + ": "
                        + cliente.getInetAddress());
            }
            long semilla = System.currentTimeMillis();
            for(Socket cliente: grupoClientes) {
                try {
                    ObjectOutputStream oos = new ObjectOutputStream(cliente.getOutputStream());
                    AsignacionGrupo asignacion = new AsignacionGrupo(idGrupo, ipMulticast, puerto, tamGrupo, semilla);
                    oos.writeObject(asignacion);
                    oos.flush();
                    log("[SERVIDOR] Enviada asignación multicast a cliente: " + cliente.getInetAddress());
                } catch (IOException e) {
                    log("[SERVIDOR ERROR] Al enviar asignación grupo: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            log("[SERVIDOR] Grupo " + idGrupo + " formado con %d clientes".formatted(tamGrupo));

        } catch (InterruptedException e) {
            log("[SERVIDOR ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void log(String msg) {
        System.out.println("[" + new Date() + "] " + msg);
    }

    public static void main(String[] args) throws IOException {
        ServidorEmparejamiento servidor = new ServidorEmparejamiento();
        servidor.start();
    }
}
