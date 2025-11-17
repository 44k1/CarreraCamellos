package cliente;

import protocolos.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClienteCamel extends JFrame {
    private String idCliente;
    private String ipMulticast;
    private int puertoMulticast;
    private int idGrupo;
    private MulticastSocket multicastSocket;
    private InetAddress grupo;
    private int miPosicion = 0;
    private final int META = 650;
    private boolean carreraTerminada = false;

    private JPanel panelPista;
    private JButton btnAvanzar;
    private JLabel lblPosicion;
    private JLabel lblEstado;

    private final int numeroJugadores = 4;
    private ConcurrentHashMap<String, Integer> posiciones;
    private ConcurrentHashMap<String, Integer> carriles;
    private Image camelImage;

    private static final Color COLOR_CALLE = new Color(204, 153, 102);
    private static final Color COLOR_LINEA_FIN = Color.BLACK;

    public ClienteCamel(String idCliente) {
        this.idCliente = idCliente;
        posiciones = new ConcurrentHashMap<>();
        carriles = new ConcurrentHashMap<>();
        cargarImagen();
        initGUI();
    }

    private void cargarImagen() {
        try {
            camelImage = ImageIO.read(new File("camel.png"));
        } catch (IOException e) {
            System.out.println("[CLIENTE] No se pudo cargar camel.png");
            camelImage = null;
        }
    }

    private void initGUI() {
        setTitle("Carrera de Camellos - Cliente: " + idCliente);
        setSize(800, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel panelInfo = new JPanel(new GridLayout(2, 1));
        lblEstado = new JLabel("Estado: Esperando conexión...", SwingConstants.CENTER);
        lblPosicion = new JLabel("Tu posición: 0 / " + META, SwingConstants.CENTER);
        lblEstado.setFont(new Font("Arial", Font.BOLD, 14));
        lblPosicion.setFont(new Font("Arial", Font.PLAIN, 12));
        panelInfo.add(lblEstado);
        panelInfo.add(lblPosicion);
        add(panelInfo, BorderLayout.NORTH);

        panelPista = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                int yEspacio = 60;
                int padding = 20;
                int pistaAlto = yEspacio - 10;
                int anchoFinish = 5;
                int finishX = META + 50;

                g.setColor(COLOR_CALLE);
                g.fillRect(0, padding/2, getWidth(), getHeight());

                for (int i = 0; i < numeroJugadores; i++) {
                    int y = padding + i * yEspacio;
                    g.setColor(new Color(200, 180, 150));
                    g.fillRect(0, y - 5, getWidth(), pistaAlto);
                    g.setColor(Color.DARK_GRAY);
                    g.drawLine(0, y + pistaAlto/2, getWidth(), y + pistaAlto/2);
                }

                g.setColor(COLOR_LINEA_FIN);
                g.fillRect(finishX, padding/2, anchoFinish, numeroJugadores * yEspacio);

                for (Map.Entry<String, Integer> p : posiciones.entrySet()) {
                    String nombre = p.getKey();
                    int pos = p.getValue();
                    Integer carril = carriles.get(nombre);

                    if (carril == null) {
                        carril = (nombre.hashCode() % numeroJugadores);
                        if (carril < 0) carril += numeroJugadores;
                        carriles.put(nombre, carril);
                    }

                    int y = padding + carril * yEspacio;
                    int x = pos;

                    if (camelImage != null) {
                        g.drawImage(camelImage, x, y, 50, 40, this);
                    } else {
                        g.setColor(Color.RED);
                        g.fillOval(x, y + 10, 40, 20);
                    }

                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Arial", Font.BOLD, 12));
                    g.drawString(nombre, x, y + 9);
                }
            }
        };

        panelPista.setPreferredSize(new Dimension(700, 300));
        panelPista.setBackground(Color.WHITE);
        add(panelPista, BorderLayout.CENTER);

        JPanel panelControl = new JPanel();
        btnAvanzar = new JButton("AVANZAR CAMELLO (1-3 pasos)");
        btnAvanzar.setFont(new Font("Arial", Font.BOLD, 16));
        btnAvanzar.setEnabled(false);
        btnAvanzar.addActionListener(e -> avanzarCamello());
        panelControl.add(btnAvanzar);
        add(panelControl, BorderLayout.SOUTH);

        setLocationRelativeTo(null);
    }

    public void conectarServidor(String ipServidor, int puertoServidor) {
        Socket socket = null;
        ObjectOutputStream oos = null;
        ObjectInputStream ois = null;

        try {
            lblEstado.setText("Estado: Conectando al servidor...");
            System.out.println("[CLIENTE] ========================================");
            System.out.println("[CLIENTE] Conectando a " + ipServidor + ":" + puertoServidor);

            socket = new Socket(ipServidor, puertoServidor);
            System.out.println("[CLIENTE] Socket conectado");

            // IMPORTANTE: Crear OOS primero y hacer flush
            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            System.out.println("[CLIENTE] ObjectOutputStream creado y flushed");

            // Ahora crear OIS
            ois = new ObjectInputStream(socket.getInputStream());
            System.out.println("[CLIENTE] ObjectInputStream creado");

            SolicitudConexion solicitud = new SolicitudConexion(idCliente);
            System.out.println("[CLIENTE] >>> Enviando SolicitudConexion: '" + solicitud.idCliente + "'");
            oos.writeObject(solicitud);
            oos.flush();
            System.out.println("[CLIENTE] Solicitud enviada");

            System.out.println("[CLIENTE] Esperando AsignacionGrupo...");
            Object objAsignacion = ois.readObject();
            System.out.println("[CLIENTE] Objeto recibido: " + objAsignacion.getClass().getSimpleName());

            if (!(objAsignacion instanceof AsignacionGrupo)) {
                System.err.println("[CLIENTE ERROR] Recibido: " + objAsignacion.getClass());
                return;
            }

            AsignacionGrupo asignacion = (AsignacionGrupo) objAsignacion;
            this.idGrupo = asignacion.idGrupo;
            this.ipMulticast = asignacion.ipMulticast;
            this.puertoMulticast = asignacion.puerto;

            System.out.println("[CLIENTE] >>> Asignación recibida:");
            System.out.println("[CLIENTE]     Grupo: " + asignacion.idGrupo);
            System.out.println("[CLIENTE]     Multicast: " + asignacion.ipMulticast + ":" + asignacion.puerto);
            System.out.println("[CLIENTE]     TamGrupo: " + asignacion.tamGrupo);

            socket.close();
            System.out.println("[CLIENTE] Socket TCP cerrado");

            posiciones.put(idCliente, 0);
            int carril = (idCliente.hashCode() % asignacion.tamGrupo);
            if (carril < 0) carril += asignacion.tamGrupo;
            carriles.put(idCliente, carril);
            System.out.println("[CLIENTE] Mi carril: " + carril);

            // Unirse a multicast
            unirCanalMulticast();

            System.out.println("[CLIENTE] ========================================");

        } catch (Exception e) {
            lblEstado.setText("ERROR: " + e.getMessage());
            System.err.println("[CLIENTE ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void unirCanalMulticast() throws Exception {
        System.out.println("[CLIENTE MCAST] Uniendo a multicast...");

        grupo = InetAddress.getByName(ipMulticast);
        System.out.println("[CLIENTE MCAST] InetAddress creado: " + grupo);

        multicastSocket = new MulticastSocket(puertoMulticast);
        multicastSocket.setReuseAddress(true);
        System.out.println("[CLIENTE MCAST] MulticastSocket creado en puerto " + puertoMulticast);

        try {
            NetworkInterface ni = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            if (ni != null) {
                multicastSocket.setNetworkInterface(ni);
                System.out.println("[CLIENTE MCAST] Interfaz configurada: " + ni.getName());
            }
        } catch (Exception e) {
            System.out.println("[CLIENTE MCAST] Warning: No se pudo configurar interfaz");
        }

        multicastSocket.joinGroup(new InetSocketAddress(grupo, puertoMulticast), null);
        System.out.println("[CLIENTE MCAST] Unido a grupo multicast " + ipMulticast + ":" + puertoMulticast);

        // Thread heartbeat
        new Thread(() -> {
            System.out.println("[CLIENTE HB] Thread iniciado");
            while (!carreraTerminada) {
                try {
                    Heartbeat hb = new Heartbeat(idCliente, System.currentTimeMillis());
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos);
                    oos.writeObject(hb);
                    oos.flush();
                    byte[] data = baos.toByteArray();
                    DatagramPacket packet = new DatagramPacket(data, data.length, grupo, puertoMulticast);
                    multicastSocket.send(packet);
                    System.out.println("[CLIENTE HB] >>> Enviado (" + data.length + " bytes)");
                    Thread.sleep(3000);
                } catch (Exception e) {
                    if (!carreraTerminada) {
                        System.err.println("[CLIENTE HB ERROR] " + e.getMessage());
                    }
                }
            }
        }).start();

        // Thread receptor
        new Thread(() -> {
            System.out.println("[CLIENTE RX] Thread iniciado");
            while (!carreraTerminada) {
                try {
                    byte[] buffer = new byte[8192];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    multicastSocket.receive(packet);
                    System.out.println("[CLIENTE RX] <<< RECIBIDO (" + packet.getLength() + " bytes)");

                    ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                    ObjectInputStream ois = new ObjectInputStream(bais);
                    Object obj = ois.readObject();

                    System.out.println("[CLIENTE RX]     Tipo: " + obj.getClass().getSimpleName());

                    if (obj instanceof EventoCarrera) {
                        EventoCarrera evento = (EventoCarrera) obj;
                        System.out.println("[CLIENTE RX]     Evento " + evento.tipo + " de '" + evento.idCliente + "' pos=" + evento.pos);
                        procesarEventoCarrera(evento);

                    } else if (obj instanceof Heartbeat) {
                        Heartbeat hb = (Heartbeat) obj;
                        System.out.println("[CLIENTE RX]     Heartbeat de '" + hb.idCliente + "'");

                    } else if (obj instanceof FinCarrera) {
                        FinCarrera fin = (FinCarrera) obj;
                        System.out.println("[CLIENTE RX]     FinCarrera");
                        procesarFinCarrera(fin);
                    }
                } catch (EOFException e) {
                    System.err.println("[CLIENTE RX] EOF");
                    break;
                } catch (Exception e) {
                    if (!carreraTerminada) {
                        System.err.println("[CLIENTE RX ERROR] " + e.getMessage());
                    }
                }
            }
        }).start();

        // Esperar un poco antes de enviar evento inicial
        Thread.sleep(500);
        enviarEvento(EventoCarrera.TipoEvento.PASO, 0);

        lblEstado.setText("Estado: En carrera - Grupo " + idGrupo);
        btnAvanzar.setEnabled(true);
        SwingUtilities.invokeLater(this::repaint);
    }

    private void avanzarCamello() {
        if (carreraTerminada) return;
        System.out.println("[CLIENTE BTN] AVANZAR PRESIONADO");

        Random r = new Random();
        int pasos = r.nextInt(3) + 1;
        miPosicion += pasos * 20;

        System.out.println("[CLIENTE BTN] Pasos: " + pasos + " -> Nueva posición: " + miPosicion);

        if (miPosicion >= META) {
            miPosicion = META;
            enviarEvento(EventoCarrera.TipoEvento.META, miPosicion);
            btnAvanzar.setEnabled(false);
            lblEstado.setText("¡HAS LLEGADO A LA META!");
        } else {
            enviarEvento(EventoCarrera.TipoEvento.PASO, miPosicion);
        }

        posiciones.put(idCliente, miPosicion);
        lblPosicion.setText("Tu posición: " + miPosicion + " / " + META);
        SwingUtilities.invokeLater(this::repaint);
    }

    private void enviarEvento(EventoCarrera.TipoEvento tipo, int pos) {
        if (multicastSocket == null) {
            System.err.println("[CLIENTE TX ERROR] multicastSocket es null!");
            return;
        }
        try {
            EventoCarrera evento = new EventoCarrera(tipo, idCliente, System.currentTimeMillis(), pos);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(evento);
            oos.flush();
            byte[] data = baos.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, grupo, puertoMulticast);
            multicastSocket.send(packet);
            System.out.println("[CLIENTE TX] >>> Evento " + tipo + " pos=" + pos + " (" + data.length + " bytes)");
        } catch (IOException e) {
            System.err.println("[CLIENTE TX ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void procesarEventoCarrera(EventoCarrera evento) {
        System.out.println("[CLIENTE PROC] Procesando evento de '" + evento.idCliente + "'");
        posiciones.put(evento.idCliente, evento.pos);
        SwingUtilities.invokeLater(this::repaint);
    }

    private void procesarFinCarrera(FinCarrera fin) {
        carreraTerminada = true;
        SwingUtilities.invokeLater(() -> {
            btnAvanzar.setEnabled(false);
            StringBuilder ranking = new StringBuilder("CARRERA FINALIZADA\nRanking:\n");
            for (int i = 0; i < fin.ranking.size(); i++) {
                ranking.append(i + 1).append(". ").append(fin.ranking.get(i)).append("\n");
            }
            lblEstado.setText("Carrera finalizada");
            JOptionPane.showMessageDialog(this, ranking.toString(), "Fin de Carrera", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String id = JOptionPane.showInputDialog("Ingrese su ID:");
            if (id == null || id.trim().isEmpty()) System.exit(0);
            ClienteCamel cliente = new ClienteCamel(id.trim());
            cliente.setVisible(true);
            new Thread(() -> {
                cliente.conectarServidor("192.168.113.120", 5000);
            }).start();
        });
    }
}
