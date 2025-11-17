package servidor;

import protocolos.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServidorEmparejamiento {

    private final int puertoControl = 5000;  // TCP
    private final int puertoMulticastBase = 6000;  // UDP multicast base
    private ServerSocket serverSocket;

    private Semaphore semaforoIdGrupo = new Semaphore(1);
    private int siguienteIdGrupo = 0;
    private final int TAM_GRUPO = 2;
    private final int MAX_GRUPOS = 3;

    private List<String> ipsMulticast = Arrays.asList(
            "239.0.0.1", "239.0.0.2", "239.0.0.3"
    );

    // Estado global por grupo: idGrupo -> Map<idCliente, posicion>
    private Map<Integer, Map<String, Integer>> grupoPosiciones = new ConcurrentHashMap<>();

    // Mapa clientes activos y su última marca de heartbeat
    private Map<String, Long> clienteUltimoHeartbeat = new ConcurrentHashMap<>();

    // Por grupo, lista de clientes socket conectados
    private Map<Integer, List<Socket>> clientesPorGrupo = new ConcurrentHashMap<>();

    // Control tiempo para timeout desconexión (ms)
    private static final long TIMEOUT_HEARTBEAT = 15000;

    public ServidorEmparejamiento() throws IOException {
        serverSocket = new ServerSocket(puertoControl);
        System.out.println("[SERVIDOR] Servidor iniciado en puerto " + puertoControl);
    }

    public void start() throws IOException {
        new Thread(this::monitorHeartbeat).start();
        System.out.println("[SERVIDOR] Esperando clientes...");
        while(true) {
            Socket cliente = serverSocket.accept();
            new Thread(() -> manejarCliente(cliente)).start();
        }
    }

    private void manejarCliente(Socket cliente) {
        try {
            ObjectInputStream ois = new ObjectInputStream(cliente.getInputStream());
            SolicitudConexion solicitud = (SolicitudConexion) ois.readObject();
            String idCliente = solicitud.idCliente;
            System.out.println("[SERVIDOR] Cliente conectado: " + idCliente);

            synchronized (this) {
                // Agregar cliente a lista esperando
                agregarClienteAEspera(cliente, idCliente);
            }
        } catch (Exception e) {
            System.err.println("[SERVIDOR] Error manejando cliente: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void agregarClienteAEspera(Socket cliente, String idCliente) throws IOException {
        // Agrega cliente a grupo pendiente o crea nuevo grupo
        clientesPorGrupo.computeIfAbsent(siguienteIdGrupo, k -> new ArrayList<>()).add(cliente);
        // Inicializa posiciones
        grupoPosiciones.computeIfAbsent(siguienteIdGrupo, k -> new ConcurrentHashMap<>())
                .put(idCliente, 0);
        // Registrar primer heartbeat
        clienteUltimoHeartbeat.put(idCliente, System.currentTimeMillis());

        if(clientesPorGrupo.get(siguienteIdGrupo).size() == TAM_GRUPO) {
            // asignar IP y puerto multicast
            asignarGrupo(siguienteIdGrupo);
            siguienteIdGrupo++;
            if(siguienteIdGrupo >= MAX_GRUPOS) {
                siguienteIdGrupo = 0; // Reutilizar
            }
        }
    }

    private void asignarGrupo(int idGrupo) throws IOException {
        List<Socket> listaClientes = clientesPorGrupo.get(idGrupo);
        String ipMulticast = ipsMulticast.get(idGrupo % ipsMulticast.size());
        int puertoMulticast = puertoMulticastBase + idGrupo;

        System.out.println("[SERVIDOR] Formando grupo " + idGrupo + " con multicast " + ipMulticast + ":" + puertoMulticast);

        AsignacionGrupo asignacion = new AsignacionGrupo(idGrupo, ipMulticast, puertoMulticast, TAM_GRUPO, System.currentTimeMillis());

        for(Socket cliente: listaClientes) {
            try {
                ObjectOutputStream oos = new ObjectOutputStream(cliente.getOutputStream());
                oos.writeObject(asignacion);
                oos.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Lanzar proxy UDP que redistribuya mensajes multicast UDP del grupo
        new Thread(() -> proxyMulticast(idGrupo, ipMulticast, puertoMulticast)).start();
    }

    private void proxyMulticast(int idGrupo, String ipMulticast, int puertoMulticast) {
        try {
            MulticastSocket msocket = new MulticastSocket(puertoMulticast);
            msocket.joinGroup(InetAddress.getByName(ipMulticast));

            byte[] buffer = new byte[8192];
            System.out.println("[SERVIDOR] Proxy multicast iniciado para grupo " + idGrupo +
                    " (" + ipMulticast + ":" + puertoMulticast + ")");

            while(true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    msocket.receive(packet);

                    // Deserializar para loguear qué se recibió
                    ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                    ObjectInputStream ois = new ObjectInputStream(bais);
                    Object obj = ois.readObject();

                    String idCliente = null;

                    if(obj instanceof EventoCarrera) {
                        EventoCarrera ev = (EventoCarrera) obj;
                        idCliente = ev.idCliente;
                        System.out.println("[SERVIDOR PROXY] Evento " + ev.tipo + " de " + ev.idCliente +
                                " pos=" + ev.pos + " en grupo " + idGrupo);

                        // Actualizar posiciones del servidor
                        Map<String, Integer> posiciones = grupoPosiciones.get(idGrupo);
                        if(posiciones != null) {
                            posiciones.put(ev.idCliente, ev.pos);
                        }

                    } else if(obj instanceof Heartbeat) {
                        Heartbeat hb = (Heartbeat) obj;
                        idCliente = hb.idCliente;
                        clienteUltimoHeartbeat.put(hb.idCliente, System.currentTimeMillis());
                        System.out.println("[SERVIDOR PROXY] Heartbeat de " + hb.idCliente);
                    }

                    // IMPORTANTE: Reenviar el paquete EXACTAMENTE igual a TODOS los clientes del grupo
                    // usando multicast (así todos lo reciben)
                    DatagramSocket reenvio = new DatagramSocket();
                    DatagramPacket envio = new DatagramPacket(
                            packet.getData(),
                            packet.getLength(),
                            InetAddress.getByName(ipMulticast),
                            puertoMulticast
                    );
                    reenvio.send(envio);
                    reenvio.close();

                    System.out.println("[SERVIDOR PROXY] Reenviado a multicast " + ipMulticast + ":" + puertoMulticast);

                } catch (EOFException e) {
                    // Ignorar fin de stream
                } catch (Exception e) {
                    System.err.println("[SERVIDOR PROXY ERROR] " + e.getMessage());
                }
            }

        } catch(Exception e) {
            System.err.println("[SERVIDOR ERROR Proxy] " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void monitorHeartbeat() {
        while(true) {
            long now = System.currentTimeMillis();
            List<String> clientesDesconectados = new ArrayList<>();

            // Detectar clientes inactivos
            for(Map.Entry<String, Long> entry: clienteUltimoHeartbeat.entrySet()) {
                if(now - entry.getValue() > TIMEOUT_HEARTBEAT) {
                    String idCliente = entry.getKey();
                    System.out.println("[SERVIDOR] Cliente desconectado (timeout): " + idCliente);
                    clientesDesconectados.add(idCliente);
                }
            }

            // Procesar desconexiones: remueve clientes y reorganiza grupos
            for(String idCliente : clientesDesconectados) {
                clienteUltimoHeartbeat.remove(idCliente);

                // Buscar y borrar cliente de posiciones y sockets
                for(Map.Entry<Integer, List<Socket>> grupoEntry: clientesPorGrupo.entrySet()) {
                    int idGrupo = grupoEntry.getKey();
                    List<Socket> clientes = grupoEntry.getValue();

                    // Eliminar socket del cliente desconectado
                    clientes.removeIf(s -> {
                        try {
                            InetAddress addr = s.getInetAddress();
                            return addr != null && addr.toString().contains(idCliente); // Mejor mantener mapeo id->socket
                        } catch (Exception e) { return false; }
                    });

                    // Borrar posición
                    Map<String, Integer> posiciones = grupoPosiciones.get(idGrupo);
                    if(posiciones != null && posiciones.containsKey(idCliente)) {
                        posiciones.remove(idCliente);
                        System.out.println("[SERVIDOR] Cliente eliminado de posiciones: " + idCliente + " grupo " + idGrupo);
                    }

                    // Si queda grupo incompleto, reorganizar o reasignar
                    if(clientes.size() < TAM_GRUPO) {
                        System.out.println("[SERVIDOR] Grupo " + idGrupo + " incompleto. Reorganizando...");
                        // Implementa política: reagrupar clientes, asignar nuevos grupos, etc.
                        // Ejemplo simplificado: quitar grupo y regresar clientes a espera para reenviar asignación
                    }
                }
            }

            try { Thread.sleep(5000); } catch(Exception e) {}
        }
    }


    public static void main(String[] args) throws IOException {
        ServidorEmparejamiento servidor = new ServidorEmparejamiento();
        servidor.start();
    }
}
