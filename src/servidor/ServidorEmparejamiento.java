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
    private final int TAM_GRUPO = 2;
    private final int MAX_GRUPOS = 3;

    private List<String> ipsMulticast = Arrays.asList(
            "239.0.0.1", "239.0.0.2", "239.0.0.3"
    );
    private int puertoMulticastBase = 6000;

    private Map<Integer, List<Socket>> clientesPorGrupo = new ConcurrentHashMap<>();
    private Map<String, Long> clienteUltimoHeartbeat = new ConcurrentHashMap<>();
    private Map<Integer, Map<String, Integer>> grupoPosiciones = new ConcurrentHashMap<>();

    // Map para guardar ObjectOutputStream por cliente
    private Map<String, ObjectOutputStream> outputStreamsPorCliente = new ConcurrentHashMap<>();

    private static final long TIMEOUT_HEARTBEAT = 20000;

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
        try {
            System.out.println("[SERVIDOR] Inicializando ObjectOutputStream...");
            oos = new ObjectOutputStream(cliente.getOutputStream());
            oos.flush();
            System.out.println("[SERVIDOR] ObjectOutputStream inicializado!");

            System.out.println("[SERVIDOR] Inicializando ObjectInputStream...");
            ois = new ObjectInputStream(cliente.getInputStream());
            System.out.println("[SERVIDOR] ObjectInputStream inicializado!");

            Object obj = ois.readObject();
            System.out.println("[SERVIDOR] Objeto recibido: " + obj.getClass().getSimpleName());

            if (!(obj instanceof SolicitudConexion)) {
                System.err.println("[SERVIDOR ERROR] Objeto no es SolicitudConexion: " + obj.getClass());
                cliente.close();
                return;
            }

            SolicitudConexion solicitud = (SolicitudConexion) obj;
            String idCliente = solicitud.idCliente;
            System.out.println("[SERVIDOR] Cliente conectado: '" + idCliente + "'");

            agregarClienteAEspera(cliente, idCliente, oos);

        } catch (Exception e) {
            System.err.println("[SERVIDOR ERROR] En manejarCliente: " + e.getMessage());
            try { if (cliente != null) cliente.close(); } catch (Exception ignored) {}
        }
    }

    private void agregarClienteAEspera(Socket cliente, String idCliente, ObjectOutputStream oos) {
        clientesPorGrupo.computeIfAbsent(siguienteIdGrupo, k -> new ArrayList<>()).add(cliente);
        grupoPosiciones.computeIfAbsent(siguienteIdGrupo, k -> new ConcurrentHashMap<>()).put(idCliente, 0);
        clienteUltimoHeartbeat.put(idCliente, System.currentTimeMillis());

        // Guardar el ObjectOutputStream una vez y reutilizarlo
        outputStreamsPorCliente.put(idCliente, oos);

        int clientesActuales = clientesPorGrupo.get(siguienteIdGrupo).size();
        System.out.println("[SERVIDOR] Clientes en grupo " + siguienteIdGrupo + ": " + clientesActuales);

        if (clientesActuales == TAM_GRUPO) {
            System.out.println("[SERVIDOR] GRUPO " + siguienteIdGrupo + " listo - asignando...");
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
        List<Socket> listaClientes = clientesPorGrupo.get(idGrupo);
        String ipMulticast = ipsMulticast.get(idGrupo % ipsMulticast.size());
        int puertoMulticast = puertoMulticastBase + idGrupo;

        AsignacionGrupo asignacion = new AsignacionGrupo(idGrupo, ipMulticast, puertoMulticast, TAM_GRUPO, System.currentTimeMillis());

        for (String clienteId : outputStreamsPorCliente.keySet()) {
            ObjectOutputStream oos = outputStreamsPorCliente.get(clienteId);
            try {
                oos.writeObject(asignacion);
                oos.flush();
                System.out.println("[SERVIDOR] AsignacionGrupo enviado a " + clienteId + "!");
            } catch (IOException e) {
                System.err.println("[SERVIDOR ERROR] Al enviar asignación a " + clienteId + ": " + e.getMessage());
            }
        }

        new Thread(() -> proxyMulticastUDP(idGrupo, ipMulticast, puertoMulticast)).start();
        System.out.println("[SERVIDOR] Proxy multicast UDP iniciado para grupo " + idGrupo);
    }

    private void proxyMulticastUDP(int idGrupo, String ipMulticast, int puertoMulticast) {
        try {
            MulticastSocket msocket = new MulticastSocket(puertoMulticast);
            msocket.setReuseAddress(true);

            InetAddress grupo = InetAddress.getByName(ipMulticast);

            // Unirse a grupo multicast en cualquier interfaz
            msocket.joinGroup(new InetSocketAddress(grupo, puertoMulticast), null);

            System.out.println("[SERVIDOR PROXY] Unido a multicast " + ipMulticast + ":" + puertoMulticast);

            DatagramSocket reenvioSocket = new DatagramSocket();

            while (true) {
                try {
                    byte[] buffer = new byte[8192];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    msocket.receive(packet);

                    System.out.println("[SERVIDOR PROXY] Paquete UDP recibido (" + packet.getLength() + " bytes)");

                    // Deserializar para loguear
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                         ObjectInputStream ois = new ObjectInputStream(bais)) {
                        Object obj = ois.readObject();
                        System.out.println("[SERVIDOR PROXY] Objeto recibido: " + obj.getClass().getSimpleName());

                        if (obj instanceof EventoCarrera) {
                            EventoCarrera ev = (EventoCarrera) obj;
                            grupoPosiciones.get(idGrupo).put(ev.idCliente, ev.pos);
                            System.out.println("[SERVIDOR PROXY] Evento tipo " + ev.tipo + " de " + ev.idCliente);
                        } else if (obj instanceof Heartbeat) {
                            Heartbeat hb = (Heartbeat) obj;
                            clienteUltimoHeartbeat.put(hb.idCliente, System.currentTimeMillis());
                            System.out.println("[SERVIDOR PROXY] Heartbeat de " + hb.idCliente);
                        }
                    } catch (Exception e) {
                        System.err.println("[SERVIDOR PROXY] Error deserializando UDP: " + e.getMessage());
                    }

                    // Reenviar el paquete EXACTO a multicast
                    DatagramPacket reenvioPacket = new DatagramPacket(packet.getData(), packet.getLength(), grupo, puertoMulticast);
                    reenvioSocket.send(reenvioPacket);
                    System.out.println("[SERVIDOR PROXY] Paquete reenviado a multicast");

                } catch (IOException e) {
                    System.err.println("[SERVIDOR PROXY] Error UDP recv/send: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[SERVIDOR PROXY ERROR] Fatal: " + e.getMessage());
            e.printStackTrace();
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
                    System.out.println("[SERVIDOR MONITOR] Cliente desconectado por timeout: " + idCliente);
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
