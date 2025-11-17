package servidor;

import protocolos.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServidorEmparejamiento {

    private final int puertoControl = 5000;
    private ServerSocket serverSocket;
    private Semaphore semaforoIdGrupo = new Semaphore(1);
    private int siguienteIdGrupo = 0;
    private final int TAM_GRUPO = 2;  // Para prueba rápida
    private final int MAX_GRUPOS = 3;

    private Map<Integer, List<Socket>> clientesPorGrupo = new ConcurrentHashMap<>();
    private Map<Integer, Map<String, Socket>> clientesMapaPorGrupo = new ConcurrentHashMap<>();
    private Map<String, Long> clienteUltimoHeartbeat = new ConcurrentHashMap<>();

    private static final long TIMEOUT_HEARTBEAT = 15000;

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
        try {
            System.out.println("[SERVIDOR] Leyendo solicitud de conexión...");
            ObjectInputStream ois = new ObjectInputStream(cliente.getInputStream());
            Object obj = ois.readObject();

            if (!(obj instanceof SolicitudConexion)) {
                System.err.println("[SERVIDOR ERROR] Objeto no es SolicitudConexion: " + obj.getClass());
                return;
            }

            SolicitudConexion solicitud = (SolicitudConexion) obj;
            String idCliente = solicitud.idCliente;
            System.out.println("[SERVIDOR] >>> Cliente conectado: '" + idCliente + "'");

            synchronized (this) {
                agregarClienteAEspera(cliente, idCliente);
            }
        } catch (Exception e) {
            System.err.println("[SERVIDOR ERROR] En manejarCliente: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void agregarClienteAEspera(Socket cliente, String idCliente) {
        System.out.println("[SERVIDOR]   -> Agregando cliente '" + idCliente + "' al grupo " + siguienteIdGrupo);

        clientesPorGrupo.computeIfAbsent(siguienteIdGrupo, k -> new ArrayList<>()).add(cliente);
        clientesMapaPorGrupo.computeIfAbsent(siguienteIdGrupo, k -> new ConcurrentHashMap<>())
                .put(idCliente, cliente);
        clienteUltimoHeartbeat.put(idCliente, System.currentTimeMillis());

        int clientesActuales = clientesPorGrupo.get(siguienteIdGrupo).size();
        System.out.println("[SERVIDOR]   -> Clientes en grupo " + siguienteIdGrupo + ": " + clientesActuales + "/" + TAM_GRUPO);

        if (clientesActuales == TAM_GRUPO) {
            System.out.println("[SERVIDOR] >>> GRUPO " + siguienteIdGrupo + " COMPLETO - Formando...");
            try {
                asignarGrupo(siguienteIdGrupo);
            } catch (IOException e) {
                System.err.println("[SERVIDOR ERROR] Al asignar grupo: " + e.getMessage());
            }
            siguienteIdGrupo++;
            if (siguienteIdGrupo >= MAX_GRUPOS) {
                siguienteIdGrupo = 0;
            }
        }
    }

    private void asignarGrupo(int idGrupo) throws IOException {
        System.out.println("[SERVIDOR] Asignando grupo " + idGrupo + "...");

        List<Socket> listaClientes = clientesPorGrupo.get(idGrupo);
        Map<String, Socket> mapClientes = clientesMapaPorGrupo.get(idGrupo);

        System.out.println("[SERVIDOR] Clientes en grupo: " + mapClientes.keySet());

        // Crear asignación (aunque no usamos multicast, la enviamos igual)
        AsignacionGrupo asignacion = new AsignacionGrupo(idGrupo, "239.0.0." + (idGrupo + 1),
                6000 + idGrupo, TAM_GRUPO,
                System.currentTimeMillis());

        for (Socket cliente : listaClientes) {
            try {
                ObjectOutputStream oos = new ObjectOutputStream(cliente.getOutputStream());
                oos.writeObject(asignacion);
                oos.flush();
                System.out.println("[SERVIDOR]   -> Asignación enviada al cliente");
            } catch (IOException e) {
                System.err.println("[SERVIDOR ERROR] Al enviar asignación: " + e.getMessage());
            }
        }

        // Lanzar proxy TCP para este grupo
        new Thread(() -> proxyTCP(idGrupo, mapClientes)).start();
        System.out.println("[SERVIDOR] >>> Proxy TCP iniciado para grupo " + idGrupo);
    }

    private void proxyTCP(int idGrupo, Map<String, Socket> clientes) {
        System.out.println("[SERVIDOR PROXY] Proxy TCP iniciado para grupo " + idGrupo);

        Map<String, ObjectInputStream> receptores = new ConcurrentHashMap<>();
        Map<String, ObjectOutputStream> emisores = new ConcurrentHashMap<>();

        // Inicializar streams
        for (Map.Entry<String, Socket> entry : clientes.entrySet()) {
            try {
                String idCliente = entry.getKey();
                Socket socket = entry.getValue();
                ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                receptores.put(idCliente, ois);
                emisores.put(idCliente, oos);
                System.out.println("[SERVIDOR PROXY] Streams inicializados para '" + idCliente + "'");
            } catch (IOException e) {
                System.err.println("[SERVIDOR PROXY ERROR] Al inicializar streams: " + e.getMessage());
            }
        }

        // Lanzar thread receptor para cada cliente
        for (Map.Entry<String, ObjectInputStream> entry : receptores.entrySet()) {
            String idCliente = entry.getKey();
            ObjectInputStream ois = entry.getValue();
            new Thread(() -> receptorProxyCliente(idGrupo, idCliente, ois, emisores, clientes.keySet())).start();
        }
    }

    private void receptorProxyCliente(int idGrupo, String idCliente, ObjectInputStream ois,
                                      Map<String, ObjectOutputStream> emisores, Set<String> todosIds) {
        System.out.println("[SERVIDOR PROXY] Receptor iniciado para '" + idCliente + "' grupo " + idGrupo);

        while (true) {
            try {
                Object obj = ois.readObject();
                System.out.println("[SERVIDOR PROXY] >>> RECIBIDO de '" + idCliente + "': " +
                        obj.getClass().getSimpleName());

                if (obj instanceof Heartbeat) {
                    Heartbeat hb = (Heartbeat) obj;
                    System.out.println("[SERVIDOR PROXY]     Heartbeat: " + hb.idCliente);
                    clienteUltimoHeartbeat.put(hb.idCliente, System.currentTimeMillis());

                } else if (obj instanceof EventoCarrera) {
                    EventoCarrera ev = (EventoCarrera) obj;
                    System.out.println("[SERVIDOR PROXY]     Evento: " + ev.tipo + " de " + ev.idCliente +
                            " pos=" + ev.pos);

                } else if (obj instanceof FinCarrera) {
                    System.out.println("[SERVIDOR PROXY]     Fin de Carrera");
                }

                // Reenviar a TODOS los clientes del grupo
                System.out.println("[SERVIDOR PROXY] >>> REENVIANDO a todos (" + todosIds + ")");
                for (String idDestino : todosIds) {
                    if (!idDestino.equals(idCliente)) {
                        ObjectOutputStream oosDestino = emisores.get(idDestino);
                        if (oosDestino != null) {
                            oosDestino.writeObject(obj);
                            oosDestino.flush();
                            System.out.println("[SERVIDOR PROXY]     Enviado a '" + idDestino + "'");
                        } else {
                            System.err.println("[SERVIDOR PROXY ERROR] No hay stream para '" + idDestino + "'");
                        }
                    }
                }

            } catch (EOFException e) {
                System.err.println("[SERVIDOR PROXY] Cliente '" + idCliente + "' desconectado (EOF)");
                break;
            } catch (Exception e) {
                System.err.println("[SERVIDOR PROXY ERROR] Para '" + idCliente + "': " + e.getMessage());
                break;
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
                    System.out.println("[SERVIDOR] !!! TIMEOUT HEARTBEAT: '" + idCliente + "'");
                    clienteUltimoHeartbeat.remove(idCliente);
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
