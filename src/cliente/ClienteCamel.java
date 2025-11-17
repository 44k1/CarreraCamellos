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
    private ConcurrentHashMap<String, Integer> posiciones;      // id -> posición
    private ConcurrentHashMap<String, Integer> carriles;        // NUEVO: id -> carril (0-3)
    private int miCarril = -1;                                   // Mi carril asignado
    private Image camelImage;

    // Cambiar a IP de la interfaz local correcta en cada cliente
    private static final String INTERFAZ_RED_LOCAL = "192.168.113.120";

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
            System.out.println("No se pudo cargar camel.png, se usará dibujo básico");
            camelImage = null;
        }
    }

    private void initGUI() {
        setTitle("Carrera de Camellos - Cliente: " + idCliente);
        setSize(800, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel panelInfo = new JPanel(new GridLayout(2, 1));
        lblEstado = new JLabel("Estado: Esperando conexión al servidor...", SwingConstants.CENTER);
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

                // Dibujar carriles
                for (int i = 0; i < numeroJugadores; i++) {
                    int y = padding + i * yEspacio;
                    g.setColor(new Color(200, 180, 150));
                    g.fillRect(0, y - 5, getWidth(), pistaAlto);
                    g.setColor(Color.DARK_GRAY);
                    g.drawLine(0, y + pistaAlto/2, getWidth(), y + pistaAlto/2);
                }

                // Línea de meta
                g.setColor(COLOR_LINEA_FIN);
                g.fillRect(finishX, padding/2, anchoFinish, numeroJugadores * yEspacio);

                // Dibujar camellos usando carriles fijos
                for (Map.Entry<String, Integer> p : posiciones.entrySet()) {
                    String nombre = p.getKey();
                    int pos = p.getValue();
                    Integer carril = carriles.get(nombre);

                    if (carril == null) {
                        // Si no tiene carril, asignarlo
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
        try {
            lblEstado.setText("Estado: Conectando...");
            System.out.println("[CLIENTE] Conectando a " + ipServidor + ":" + puertoServidor);

            Socket socket = new Socket(ipServidor, puertoServidor);
            System.out.println("[CLIENTE] Socket conectado");

            ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
            SolicitudConexion solicitud = new SolicitudConexion(idCliente);
            System.out.println("[CLIENTE] Enviando solicitud: " + solicitud.idCliente);
            oos.writeObject(solicitud);
            oos.flush();

            ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
            System.out.println("[CLIENTE] Esperando asignación...");
            AsignacionGrupo asignacion = (AsignacionGrupo) ois.readObject();

            if (asignacion == null) {
                System.err.println("[CLIENTE ERROR] Asignación es null!");
                return;
            }

            System.out.println("[CLIENTE] Asignación recibida: Grupo " + asignacion.idGrupo);

            this.idGrupo = asignacion.idGrupo;
            this.ipMulticast = asignacion.ipMulticast;
            this.puertoMulticast = asignacion.puerto;

            // Asignar carril basado en el hash del ID
            int carril = (idCliente.hashCode() % asignacion.tamGrupo);
            if (carril < 0) carril += asignacion.tamGrupo;
            miCarril = carril;
            carriles.put(idCliente, carril);

            System.out.println("[CLIENTE] Mi carril asignado: " + miCarril + " de " + asignacion.tamGrupo);

            socket.close();

            posiciones.put(idCliente, 0);

            System.out.println("[CLIENTE] Configuración recibida - Grupo: " + idGrupo +
                    " Multicast: " + ipMulticast + ":" + puertoMulticast);

            unirCanalMulticast();

            // Enviar evento inicial
            enviarEvento(EventoCarrera.TipoEvento.PASO, 0);

            System.out.println("[CLIENTE] Evento PASO inicial enviado");

            lblEstado.setText("Estado: En carrera - Grupo " + idGrupo);
            btnAvanzar.setEnabled(true);
            repaint();

        } catch (Exception e) {
            lblEstado.setText("ERROR: " + e.getMessage());
            System.err.println("[CLIENTE] Error conectando: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void unirCanalMulticast() throws IOException {
        grupo = InetAddress.getByName(ipMulticast);
        multicastSocket = new MulticastSocket(puertoMulticast);

        NetworkInterface ni = null;
        try {
            ni = NetworkInterface.getByInetAddress(InetAddress.getByName(INTERFAZ_RED_LOCAL));
            multicastSocket.setNetworkInterface(ni);
            System.out.println("[CLIENTE] Interfaz de red configurada: " + INTERFAZ_RED_LOCAL);
        } catch (Exception e) {
            System.out.println("[CLIENTE] Warning: No se pudo configurar interfaz de red, usando default");
        }

        multicastSocket.joinGroup(grupo);
        System.out.println("[CLIENTE] Unido a multicast " + ipMulticast + ":" + puertoMulticast);

        // THREAD HEARTBEAT - CRÍTICO
        new Thread(() -> {
            System.out.println("[CLIENTE] Thread heartbeat iniciado");
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
                    System.out.println("[CLIENTE] Heartbeat enviado (" + data.length + " bytes)");
                    Thread.sleep(3000);
                } catch (Exception e) {
                    System.err.println("[CLIENTE] Error en heartbeat: " + e.getMessage());
                }
            }
        }).start();

        // THREAD RECEPTOR
        new Thread(() -> {
            System.out.println("[CLIENTE] Thread receptor iniciado");
            while (!carreraTerminada) {
                try {
                    byte[] buffer = new byte[4096];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    multicastSocket.receive(packet);
                    System.out.println("[CLIENTE] Paquete recibido (" + packet.getLength() + " bytes)");

                    ByteArrayInputStream bais = new ByteArrayInputStream(packet.getData(), 0, packet.getLength());
                    ObjectInputStream ois = new ObjectInputStream(bais);
                    Object obj = ois.readObject();

                    if (obj instanceof EventoCarrera) {
                        EventoCarrera evento = (EventoCarrera) obj;
                        System.out.println("[CLIENTE] Evento " + evento.tipo + " de " + evento.idCliente +
                                " pos=" + evento.pos);
                        procesarEventoCarrera(evento);
                    } else if (obj instanceof Heartbeat) {
                        Heartbeat hb = (Heartbeat) obj;
                        System.out.println("[CLIENTE] Heartbeat recibido de " + hb.idCliente);
                    } else if (obj instanceof FinCarrera) {
                        FinCarrera fin = (FinCarrera) obj;
                        System.out.println("[CLIENTE] Fin de carrera recibido");
                        procesarFinCarrera(fin);
                    }
                } catch (Exception e) {
                    if (!carreraTerminada) System.err.println("[CLIENTE] Error receptor: " + e.getMessage());
                }
            }
        }).start();
    }

    private void avanzarCamello() {
        if (carreraTerminada) return;
        Random r = new Random();
        int pasos = r.nextInt(3) + 1;
        miPosicion += pasos * 20;
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
        if (grupo == null || multicastSocket == null) {
            System.err.println("[CLIENTE ERROR] grupo o multicastSocket es null!");
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
            System.out.println("[CLIENTE] Evento " + tipo + " enviado");
        } catch (IOException e) {
            System.err.println("[CLIENTE ERROR] Error enviando evento: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void procesarEventoCarrera(EventoCarrera evento) {
        System.out.println("[CLIENTE] Procesando evento de " + evento.idCliente);
        posiciones.put(evento.idCliente, evento.pos);

        // Asignar carril si no lo tiene
        Integer carril = carriles.get(evento.idCliente);
        if (carril == null) {
            carril = (evento.idCliente.hashCode() % numeroJugadores);
            if (carril < 0) carril += numeroJugadores;
            carriles.put(evento.idCliente, carril);
            System.out.println("[CLIENTE] Carril asignado a " + evento.idCliente + ": " + carril);
        }

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
            JOptionPane.showMessageDialog(this, ranking.toString(), "Fin de Carrera",
                    JOptionPane.INFORMATION_MESSAGE);
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
