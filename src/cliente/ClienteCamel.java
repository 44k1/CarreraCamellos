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
    private LinkedHashMap<String, Integer> carriles;  // Carriles secuenciales
    private Image camelImage;

    private static final Color COLOR_CALLE = new Color(204, 153, 102);
    private static final Color COLOR_LINEA_FIN = Color.BLACK;

    public ClienteCamel(String idCliente) {
        this.idCliente = idCliente;
        posiciones = new ConcurrentHashMap<>();
        carriles = new LinkedHashMap<>();  // Mantiene orden de inserción
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

                // Dibujar todos los camellos
                for (Map.Entry<String, Integer> p : posiciones.entrySet()) {
                    String nombre = p.getKey();
                    int pos = p.getValue();
                    Integer carril = carriles.get(nombre);

                    if (carril == null) carril = 0;

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
            carriles.put(idCliente, 0);  // Mi camello siempre es el primero en llegar

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

                        // Si es un nuevo camello, asignarle el siguiente carril disponible
                        if (!carriles.containsKey(evento.idCliente)) {
                            int nuevoCarril = carriles.size();
                            carriles.put(evento.idCliente, nuevoCarril);
                            System.out.println("[CLIENTE] Carril asignado a '" + evento.idCliente + "': " + nuevoCarril);
                        }

                        SwingUtilities.invokeLater(this::repaint);

                    } else if (obj instanceof FinCarrera) {
                        carreraTerminada = true;
                        FinCarrera fin = (FinCarrera) obj;
                        SwingUtilities.invokeLater(() -> mostrarPodio(fin));
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

    private void mostrarPodio(FinCarrera fin) {
        btnAvanzar.setEnabled(false);
        lblEstado.setText("¡CARRERA FINALIZADA!");

        // Crear diálogo con las mismas dimensiones que la ventana principal
        JDialog dialogo = new JDialog(this, "🏆 PODIO FINAL - Grupo " + fin.idGrupo + " 🏆", true);
        dialogo.setSize(800, 400);  // Mismo tamaño que la ventana principal
        dialogo.setLayout(new BorderLayout());
        dialogo.setLocationRelativeTo(this);

        // Panel con el podio (ocupa todo el ancho)
        JPanel panelPodio = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;

                // Fondo degradado
                GradientPaint gradient = new GradientPaint(0, 0, new Color(240, 240, 255), 0, getHeight(), new Color(200, 220, 255));
                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // Título
                g.setColor(new Color(50, 50, 100));
                g.setFont(new Font("Arial", Font.BOLD, 28));
                String titulo = "🏆 PODIO FINAL 🏆";
                FontMetrics fm = g.getFontMetrics();
                int tituloX = (getWidth() - fm.stringWidth(titulo)) / 2;
                g.drawString(titulo, tituloX, 40);

                // Posiciones del podio (centrado en la ventana)
                int anchoTotal = getWidth();
                int anchoPodio = 120;
                int espaciado = 40;
                int centroX = anchoTotal / 2;

                int[] posiciones_podio = {
                        centroX - anchoPodio - espaciado,      // 2º (izquierda)
                        centroX - anchoPodio / 2,               // 1º (centro)
                        centroX + espaciado                     // 3º (derecha)
                };

                int[] orden = {1, 0, 2};  // Para dibujar en orden 2º, 1º, 3º
                int[] alturas_podio = {140, 180, 100};     // Altura del podio (1º más alto)
                Color[] colores = {
                        new Color(255, 215, 0),      // Oro
                        new Color(192, 192, 192),    // Plata
                        new Color(205, 127, 50)      // Bronce
                };
                String[] medallas = {"🥇", "🥈", "🥉"};
                String[] textoMedallas = {"ORO", "PLATA", "BRONCE"};

                // Dibujar podios
                for (int idx = 0; idx < 3; idx++) {
                    int i = orden[idx];
                    if (i >= fin.ranking.size()) continue;

                    String nombre = fin.ranking.get(i);
                    int x = posiciones_podio[idx];
                    int altura = alturas_podio[i];

                    // Dibujar sombra del podio
                    g.setColor(new Color(0, 0, 0, 30));
                    g.fillRect(x + 5, getHeight() - altura + 5, anchoPodio, altura);

                    // Dibujar podio con degradado
                    GradientPaint podioGradient = new GradientPaint(
                            x, getHeight() - altura, colores[i].brighter(),
                            x, getHeight(), colores[i].darker()
                    );
                    g2d.setPaint(podioGradient);
                    g2d.fillRect(x, getHeight() - altura, anchoPodio, altura);

                    // Borde del podio
                    g.setColor(colores[i].darker().darker());
                    g2d.setStroke(new BasicStroke(3));
                    g2d.drawRect(x, getHeight() - altura, anchoPodio, altura);

                    // Número de posición en el podio
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("Arial", Font.BOLD, 48));
                    String posText = String.valueOf(i + 1);
                    fm = g.getFontMetrics();
                    int posX = x + (anchoPodio - fm.stringWidth(posText)) / 2;
                    int posY = getHeight() - altura / 2 + 15;

                    // Sombra del número
                    g.setColor(new Color(0, 0, 0, 100));
                    g.drawString(posText, posX + 2, posY + 2);

                    // Número
                    g.setColor(Color.WHITE);
                    g.drawString(posText, posX, posY);

                    // Medalla encima del podio
                    g.setFont(new Font("Arial", Font.PLAIN, 40));
                    String medalla = medallas[i];
                    fm = g.getFontMetrics();
                    int medallaX = x + (anchoPodio - fm.stringWidth(medalla)) / 2;
                    g.drawString(medalla, medallaX, getHeight() - altura - 50);

                    // Texto de medalla
                    g.setColor(new Color(50, 50, 100));
                    g.setFont(new Font("Arial", Font.BOLD, 12));
                    fm = g.getFontMetrics();
                    int textoX = x + (anchoPodio - fm.stringWidth(textoMedallas[i])) / 2;
                    g.drawString(textoMedallas[i], textoX, getHeight() - altura - 30);

                    // Nombre del camello debajo del podio
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Arial", Font.BOLD, 14));
                    fm = g.getFontMetrics();
                    int nombreX = x + (anchoPodio - fm.stringWidth(nombre)) / 2;

                    // Fondo para el nombre
                    g.setColor(new Color(255, 255, 255, 200));
                    g.fillRoundRect(nombreX - 5, getHeight() - 25, fm.stringWidth(nombre) + 10, 20, 10, 10);

                    g.setColor(new Color(50, 50, 100));
                    g.drawString(nombre, nombreX, getHeight() - 10);
                }

                // Mostrar resto de participantes si hay más de 3
                if (fin.ranking.size() > 3) {
                    g.setColor(new Color(50, 50, 100));
                    g.setFont(new Font("Arial", Font.PLAIN, 12));
                    int yPos = 80;
                    g.drawString("Otros participantes:", 20, yPos);
                    for (int i = 3; i < fin.ranking.size(); i++) {
                        yPos += 20;
                        g.drawString((i + 1) + ". " + fin.ranking.get(i), 20, yPos);
                    }
                }
            }
        };

        dialogo.add(panelPodio, BorderLayout.CENTER);

        // Panel inferior con botón de cierre
        JPanel panelInferior = new JPanel();
        panelInferior.setBackground(new Color(240, 240, 255));
        panelInferior.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JButton btnCerrar = new JButton("Cerrar");
        btnCerrar.setFont(new Font("Arial", Font.BOLD, 14));
        btnCerrar.setPreferredSize(new Dimension(120, 35));
        btnCerrar.setBackground(new Color(100, 150, 255));
        btnCerrar.setForeground(Color.WHITE);
        btnCerrar.setFocusPainted(false);
        btnCerrar.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));
        btnCerrar.addActionListener(e -> dialogo.dispose());

        panelInferior.add(btnCerrar);
        dialogo.add(panelInferior, BorderLayout.SOUTH);

        dialogo.setVisible(true);
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
