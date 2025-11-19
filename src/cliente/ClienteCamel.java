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
    private int idGrupo;
    private Socket socketServidor;
    private ObjectOutputStream oosServidor;
    private ObjectInputStream oisServidor;

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
                        carril = Math.abs(nombre.hashCode() % numeroJugadores);
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

            socketServidor = new Socket(ipServidor, puertoServidor);
            oosServidor = new ObjectOutputStream(socketServidor.getOutputStream());
            oosServidor.flush();
            oisServidor = new ObjectInputStream(socketServidor.getInputStream());

            SolicitudConexion solicitud = new SolicitudConexion(idCliente);
            oosServidor.writeObject(solicitud);
            oosServidor.flush();

            AsignacionGrupo asignacion = (AsignacionGrupo) oisServidor.readObject();
            this.idGrupo = asignacion.idGrupo;

            System.out.println("[CLIENTE] Asignado a grupo " + idGrupo);

            posiciones.put(idCliente, 0);
            int carril = Math.abs(idCliente.hashCode() % asignacion.tamGrupo);
            carriles.put(idCliente, carril);

            // Thread heartbeat
            iniciarHeartbeat();

            // Thread receptor
            iniciarReceptor();

            lblEstado.setText("En carrera - Grupo " + idGrupo);
            btnAvanzar.setEnabled(true);
            SwingUtilities.invokeLater(this::repaint);

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
        }
    }

    private void iniciarHeartbeat() {
        new Thread(() -> {
            while (!carreraTerminada) {
                try {
                    Heartbeat hb = new Heartbeat(idCliente, System.currentTimeMillis());
                    oosServidor.writeObject(hb);
                    oosServidor.flush();
                    Thread.sleep(3000);
                } catch (Exception e) {
                    if (!carreraTerminada) {
                        System.err.println("[CLIENTE HB ERROR] " + e.getMessage());
                    }
                    break;
                }
            }
        }).start();
    }

    private void iniciarReceptor() {
        new Thread(() -> {
            while (!carreraTerminada) {
                try {
                    Object obj = oisServidor.readObject();

                    if (obj instanceof EventoCarrera) {
                        EventoCarrera evento = (EventoCarrera) obj;
                        System.out.println("[CLIENTE RX] Evento de '" + evento.idCliente + "' pos=" + evento.pos);

                        posiciones.put(evento.idCliente, evento.pos);

                        // Asignar carril si no tiene
                        if (!carriles.containsKey(evento.idCliente)) {
                            int carril = Math.abs(evento.idCliente.hashCode() % numeroJugadores);
                            carriles.put(evento.idCliente, carril);
                        }

                        SwingUtilities.invokeLater(this::repaint);

                    } else if (obj instanceof FinCarrera) {
                        carreraTerminada = true;
                        FinCarrera fin = (FinCarrera) obj;
                        SwingUtilities.invokeLater(() -> mostrarRanking(fin));
                    }

                } catch (EOFException e) {
                    System.err.println("[CLIENTE] Servidor cerró conexión");
                    break;
                } catch (Exception e) {
                    if (!carreraTerminada) {
                        System.err.println("[CLIENTE RX ERROR] " + e.getMessage());
                    }
                    break;
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
        try {
            EventoCarrera evento = new EventoCarrera(tipo, idCliente, System.currentTimeMillis(), pos);
            oosServidor.writeObject(evento);
            oosServidor.flush();
            System.out.println("[CLIENTE TX] Evento " + tipo + " pos=" + pos);
        } catch (Exception e) {
            System.err.println("[CLIENTE TX ERROR] " + e.getMessage());
        }
    }

    private void mostrarRanking(FinCarrera fin) {
        btnAvanzar.setEnabled(false);
        StringBuilder ranking = new StringBuilder("CARRERA FINALIZADA\nRanking:\n");
        for (int i = 0; i < fin.ranking.size(); i++) {
            ranking.append(i + 1).append(". ").append(fin.ranking.get(i)).append("\n");
        }
        lblEstado.setText("Carrera finalizada");
        JOptionPane.showMessageDialog(this, ranking.toString(), "Fin de Carrera", JOptionPane.INFORMATION_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String id = JOptionPane.showInputDialog("Ingrese su ID:");
            if (id == null || id.trim().isEmpty()) System.exit(0);
            ClienteCamel cliente = new ClienteCamel(id.trim());
            cliente.setVisible(true);
            new Thread(() -> {
                cliente.conectarServidor("localhost", 5000);
            }).start();
        });
    }
}
