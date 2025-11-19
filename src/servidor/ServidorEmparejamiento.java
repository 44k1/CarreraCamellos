package servidor;

import protocolos.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServidorEmparejamiento {

    private final int puertoControl = 5000;
    private ServerSocket serverSocket;
    private int siguienteIdGrupo = 0;
    private final int TAM_GRUPO = 2;
    private final int MAX_GRUPOS = 3;

    private List<String> ipsMulticast = Arrays.asList(
            "239.0.0.1", "239.0.0.2", "239.0.0.3"
    );
    private int puertoMulticastBase = 6000;

    // Guardar información de clientes por grupo
    private Map<Integer, List<ClienteInfo>> clientesPorGrupo = new ConcurrentHashMap<>();
    private Map<String, Long> clienteUltimoHeartbeat = new ConcurrentHashMap<>();
    private Map<Integer, Map<String, Integer>> grupoPosiciones = new ConcurrentHashMap<>();

    private static final long TIMEOUT_HEARTBEAT = 20000;

    // Clase interna para guardar info de cliente
    private static class ClienteInfo {
        String id;
        Socket socket;
        ObjectOutputStream oos;
        ObjectInputStream ois;

        ClienteInfo(String id, Socket socket, ObjectOutputStream oos, ObjectInputStream ois) {
            this.id = id;
            this.socket = socket;
            this.oos = oos;
            this.ois = ois;
        }
    }

    public ServidorEmparejamiento() throws IOException {
        serverSocket = new ServerSocket(puertoControl);
        System.out.println("[SERVIDOR] ========================================");
        System.out.println("[SERVIDOR] Servidor iniciado en puerto " + puertoControl);
        System.out.println("[SERVIDOR] TAM_GRUPO = " + TAM_GRUPO);
        System.out.println("[SERVIDOR] ========================================");
    }

    public void start() throws IOException {
        new Thread(this::monitorHeartbeat).start();
        System.out.println("[SERVIDOR] Esperando clientes...");

        while (true) {
            try {
                Socket cliente = serverSocket.accept();
                System.out.println("[SERVIDOR] >>> NUEVA CONEXIÓN desde " + cliente.getInetAddress());
                new Thread(() -> manejarCliente(cliente)).start();
            } catch (Exception e) {
                System.err.println("[SERVIDOR ERROR] Al aceptar cliente: " + e.getMessage());
            }
        }
    }

    private void manejarCliente(Socket cliente) {
        ObjectOutputStream oos = null;
        ObjectInputStream ois = null;
        String idCliente = null;

        try {
            System.out.println("[SERVIDOR] Inicializando streams...");
            oos = new ObjectOutputStream(cliente.getOutputStream());
            oos.flush();
            ois = new ObjectInputStream(cliente.getInputStream());
            System.out.println("[SERVIDOR] Streams inicializados!");

            Object obj = ois.readObject();
            if (!(obj instanceof SolicitudConexion)) {
                System.err.println("[SERVIDOR ERROR] Objeto no es SolicitudConexion");
                cliente.close();
                return;
            }

            SolicitudConexion solicitud = (SolicitudConexion) obj;
            idCliente = solicitud.idCliente;
            System.out.println("[SERVIDOR] Cliente conectado: '" + idCliente + "'");

            int idGrupo = agregarClienteAEspera(idCliente, cliente, oos, ois);

            // Mantener conexión abierta y escuchar eventos del cliente
            escucharEventosCliente(idCliente, idGrupo, ois);

        } catch (Exception e) {
            System.err.println("[SERVIDOR ERROR] En manejarCliente '" + idCliente + "': " + e.getMessage());
        } finally {
            if (idCliente != null) {
                clienteUltimoHeartbeat.remove(idCliente);
                System.out.println("[SERVIDOR] Cliente desconectado: " + idCliente);
            }
        }
    }

    private int agregarClienteAEspera(String idCliente, Socket socket, ObjectOutputStream oos, ObjectInputStream ois) throws IOException {
        synchronized (this) {
            int idGrupo = siguienteIdGrupo;

            ClienteInfo info = new ClienteInfo(idCliente, socket, oos, ois);
            clientesPorGrupo.computeIfAbsent(idGrupo, k -> new ArrayList<>()).add(info);
            grupoPosiciones.computeIfAbsent(idGrupo, k -> new ConcurrentHashMap<>()).put(idCliente, 0);
            clienteUltimoHeartbeat.put(idCliente, System.currentTimeMillis());

            int clientesActuales = clientesPorGrupo.get(idGrupo).size();
            System.out.println("[SERVIDOR] Clientes en grupo " + idGrupo + ": " + clientesActuales + "/" + TAM_GRUPO);

            if (clientesActuales == TAM_GRUPO) {
                System.out.println("[SERVIDOR] GRUPO " + idGrupo + " completo - asignando...");
                asignarGrupo(idGrupo);
                siguienteIdGrupo = (siguienteIdGrupo + 1) % MAX_GRUPOS;
            }

            return idGrupo;
        }
    }

    private void asignarGrupo(int idGrupo) throws IOException {
        List<ClienteInfo> listaClientes = clientesPorGrupo.get(idGrupo);
        String ipMulticast = ipsMulticast.get(idGrupo % ipsMulticast.size());
        int puertoMulticast = puertoMulticastBase + idGrupo;

        AsignacionGrupo asignacion = new AsignacionGrupo(idGrupo, ipMulticast, puertoMulticast, TAM_GRUPO, System.currentTimeMillis());

        for (ClienteInfo info : listaClientes) {
            try {
                info.oos.writeObject(asignacion);
                info.oos.flush();
                System.out.println("[SERVIDOR] AsignacionGrupo enviado a " + info.id);
            } catch (IOException e) {
                System.err.println("[SERVIDOR ERROR] Al enviar asignación a " + info.id + ": " + e.getMessage());
            }
        }

        System.out.println("[SERVIDOR] Grupo " + idGrupo + " iniciado con " + listaClientes.size() + " clientes");
    }

    private void escucharEventosCliente(String idCliente, int idGrupo, ObjectInputStream ois) {
        System.out.println("[SERVIDOR] Escuchando eventos de '" + idCliente + "' en grupo " + idGrupo);

        while (true) {
            try {
                Object obj = ois.readObject();

                if (obj instanceof EventoCarrera) {
                    EventoCarrera evento = (EventoCarrera) obj;
                    System.out.println("[SERVIDOR] Evento " + evento.tipo + " de '" + evento.idCliente + "' pos=" + evento.pos);

                    // Actualizar posición
                    grupoPosiciones.get(idGrupo).put(evento.idCliente, evento.pos);

                    // Redistribuir a TODOS los clientes del grupo EXCEPTO el emisor
                    redistribuirEvento(idGrupo, evento, idCliente);

                } else if (obj instanceof Heartbeat) {
                    Heartbeat hb = (Heartbeat) obj;
                    clienteUltimoHeartbeat.put(hb.idCliente, System.currentTimeMillis());
                    System.out.println("[SERVIDOR] Heartbeat de '" + hb.idCliente + "'");
                }

            } catch (EOFException e) {
                System.out.println("[SERVIDOR] Cliente '" + idCliente + "' cerró conexión");
                break;
            } catch (Exception e) {
                System.err.println("[SERVIDOR ERROR] Leyendo evento de '" + idCliente + "': " + e.getMessage());
                break;
            }
        }
    }

    private void redistribuirEvento(int idGrupo, EventoCarrera evento, String emisor) {
        List<ClienteInfo> clientes = clientesPorGrupo.get(idGrupo);
        if (clientes == null) return;

        for (ClienteInfo info : clientes) {
            // NO enviar al cliente que envió el evento originalmente
            if (info.id.equals(emisor)) {
                continue;
            }

            try {
                info.oos.writeObject(evento);
                info.oos.flush();
                System.out.println("[SERVIDOR] Evento reenviado a '" + info.id + "'");
            } catch (IOException e) {
                System.err.println("[SERVIDOR ERROR] Al reenviar evento a '" + info.id + "': " + e.getMessage());
            }
        }
    }

    private void monitorHeartbeat() {
        while (true) {
            try {
                Thread.sleep(5000);
                long now = System.currentTimeMillis();
                List<String> desconectados = new ArrayList<>();

                for (Map.Entry<String, Long> entry : clienteUltimoHeartbeat.entrySet()) {
                    if (now - entry.getValue() > TIMEOUT_HEARTBEAT) {
                        desconectados.add(entry.getKey());
                    }
                }

                for (String idCliente : desconectados) {
                    clienteUltimoHeartbeat.remove(idCliente);
                    System.out.println("[SERVIDOR MONITOR] Cliente timeout: " + idCliente);
                }
            } catch (Exception e) {
                System.err.println("[SERVIDOR MONITOR ERROR] " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) throws IOException {
        ServidorEmparejamiento servidor = new ServidorEmparejamiento();
        servidor.start();
    }
}
