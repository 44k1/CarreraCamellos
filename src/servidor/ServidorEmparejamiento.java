package servidor;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import protocolos.*;
// Usa las clases protocolo arriba definidas o importalas donde corresponda

public class ServidorEmparejamiento {
    private final int puertoControl = 5000;
    private ServerSocket serverSocket;
    private Semaphore semaforoIdGrupo = new Semaphore(1);
    private int siguienteIdGrupo = 0;
    private final int MAX_GRUPOS = 3;
    private List<Socket> esperaClientes = Collections.synchronizedList(new ArrayList<>());
    private List<String> ipsMulticast = Arrays.asList("230.0.0.1", "230.0.0.2", "230.0.0.3");
    private int puertoMulticastBase = 6000;

    public ServidorEmparejamiento() throws IOException {
        serverSocket = new ServerSocket(puertoControl);
        log("[SERVIDOR] Servidor iniciado en puerto " + puertoControl);
    }

    public void start() throws IOException {
        log("[SERVIDOR] Esperando clientes...");
        while(true) {
            Socket cliente = serverSocket.accept();
            log("[SERVIDOR] Conexión aceptada desde: " + cliente.getInetAddress());
            new Thread(() -> {
                try {
                    ObjectInputStream ois = new ObjectInputStream(cliente.getInputStream());
                    SolicitudConexion solicitud = (SolicitudConexion) ois.readObject();
                    log("[SERVIDOR] Solicitud de conexión recibida de cliente: " + solicitud.idCliente);

                    synchronized (esperaClientes) {
                        esperaClientes.add(cliente);
                        log("[SERVIDOR] Clientes en espera: " + esperaClientes.size() + "/4");

                        if (esperaClientes.size() >= 4) {
                            formarGrupo(4);
                        }
                    }
                } catch (Exception ex) {
                    log("[SERVIDOR ERROR] Excepción procesando cliente: " + ex.getMessage());
                    ex.printStackTrace();
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
            log("[SERVIDOR] Formando grupo ID: " + idGrupo + " con IP Multicast: " + ipMulticast + " y puerto: " + puerto);

            List<Socket> grupoClientes = new ArrayList<>();
            for (int i=0; i<tamGrupo; i++) {
                Socket cliente = esperaClientes.remove(0);
                log("[SERVIDOR] Cliente removido de espera para grupo " + idGrupo + ": " + cliente.getInetAddress());
                grupoClientes.add(cliente);
            }
            long semilla = System.currentTimeMillis();
            for(Socket cliente: grupoClientes) {
                try {
                    ObjectOutputStream oos = new ObjectOutputStream(cliente.getOutputStream());
                    AsignacionGrupo asignacion = new AsignacionGrupo(idGrupo, ipMulticast, puerto, tamGrupo, semilla);
                    oos.writeObject(asignacion);
                    oos.flush();
                    log("[SERVIDOR] Asignación enviada a cliente " + cliente.getInetAddress() + " para grupo " + idGrupo);
                } catch (IOException e) {
                    log("[SERVIDOR ERROR] No se pudo enviar asignación a cliente " + cliente.getInetAddress() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            log("[SERVIDOR] Grupo " + idGrupo + " formado correctamente con " + tamGrupo + " clientes");

        } catch (InterruptedException e) {
            log("[SERVIDOR ERROR] Error al formar grupo: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void log(String mensaje) {
        System.out.println("[" + new Date() + "] " + mensaje);
    }

    public static void main(String[] args) throws IOException {
        ServidorEmparejamiento servidor = new ServidorEmparejamiento();
        servidor.start();
    }
}
