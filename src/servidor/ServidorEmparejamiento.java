package servidor;

import protocolos.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServidorEmparejamiento {

    private final int puertoControl = 5000;  // TCP solo para asignación inicial
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
        grupoPosiciones.computeIfAbsent(siguienteIdGrupo, k -> new ConcurrentHashMap<>()).put(idCliente, 0);
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
        String ipMulticast = ipsMulticast.get(idGrupo % ipsMulticast.size());
        int puertoMulticast = puertoMulticastBase + idGrupo;

        System.out.println("[SERVIDOR] Grupo " + idGrupo + " -> " + ipMulticast + ":" + puertoMulticast);

        AsignacionGrupo asignacion = new AsignacionGrupo(idGrupo, ipMulticast, puertoMulticast, TAM_GRUPO, System.currentTimeMillis());

        for (Socket cliente : listaClientes) {
            try {
                ObjectOutputStream oos = new ObjectOutputStream(cliente.getOutputStream());
                System.out.println("[SERVIDOR]   -> Enviando AsignacionGrupo a cliente");
                oos.writeObject(asignacion);
                oos.flush();
            } catch (IOException e) {
                System.err.println("[SERVIDOR ERROR] Al enviar asignación: " + e.getMessage());
            }
        }

        // Lanzar proxy UDP multicast para este grupo
        new Thread(() -> proxyMulticastUDP(idGrupo, ipMulticast, puertoMulticast)).start();
        System.out.println("[SERVIDOR] >>> Proxy UDP multicast iniciado para grupo " + idGrupo);
    }

    private void proxyMulticastUDP(int idGrupo, String ipMulticast, int puertoMulticast) {
        try {
            System.out.println("[SERVIDOR PROXY] Escuchando multicast " + ipMulticast + ":" + puertoMulticast);

            MulticastSocket msocket = new MulticastSocket(puertoMulticast);
            msocket.setReuseAddress(true);
            InetAddress grupo = InetAddress.getByName(ipMulticast);

            // Unirse a multicast en todas las interfaces
            msocket.joinGroup(new InetSocketAddress(grupo, puertoMulticast), null);

            System.out.println("[SERVIDOR PROXY] Unido a multicast grupo");

            byte[] buffer = new byte[8192];
            DatagramSocket reenvio = new DatagramSocket();

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                msocket.receive(packet);

                System.out.println("[SERVIDOR PROXY] >>> RECIBIDO paquete (" + packet.getLength() + " bytes)");

                try {
                    ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                    ObjectInputStream ois = new ObjectInputStream(bais);
                    Object obj = ois.readObject();

                    if (obj instanceof EventoCarrera) {
                        EventoCarrera ev = (EventoCarrera) obj;
                        System.out.println("[SERVIDOR PROXY]     Evento: " + ev.tipo + " de '" + ev.idCliente + "' pos=" + ev.pos);
                        grupoPosiciones.get(idGrupo).put(ev.idCliente, ev.pos);

                    } else if (obj instanceof Heartbeat) {
                        Heartbeat hb = (Heartbeat) obj;
                        System.out.println("[SERVIDOR PROXY]     Heartbeat de '" + hb.idCliente + "'");
                        clienteUltimoHeartbeat.put(hb.idCliente, System.currentTimeMillis());

                    } else if (obj instanceof FinCarrera) {
                        System.out.println("[SERVIDOR PROXY]     FinCarrera");
                    }
                } catch (Exception e) {
                    System.err.println("[SERVIDOR PROXY ERROR] Deserializar: " + e.getMessage());
                }

                // REENVIAR el paquete exactamente igual a multicast (para que lo reciban todos)
                System.out.println("[SERVIDOR PROXY] >>> REENVIANDO a multicast");
                DatagramPacket reenvioPacket = new DatagramPacket(
                        packet.getData(),
                        packet.getLength(),
                        grupo,
                        puertoMulticast
                );
                reenvio.send(reenvioPacket);
                System.out.println("[SERVIDOR PROXY]     Reenviado");
            }

        } catch (Exception e) {
            System.err.println("[SERVIDOR PROXY ERROR] " + e.getMessage());
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
                    long diff = now - entry.getValue();
                    if (diff > TIMEOUT_HEARTBEAT) {
                        desconectados.add(entry.getKey());
                        System.out.println("[SERVIDOR MONITOR] !!! TIMEOUT '" + entry.getKey() + "' (" + diff + "ms)");
                    }
                }

                for (String idCliente : desconectados) {
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
