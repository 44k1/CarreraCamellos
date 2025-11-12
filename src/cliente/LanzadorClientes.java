package cliente;


public class LanzadorClientes {
    public static void main(String[] args) {
        String servidorIp = "localhost";
        int puertoServidor = 5000;

        // Crear 4 clientes con IDs Jugador1 a Jugador4
        for (int i = 1; i <= 4; i++) {
            String idCliente = "Jugador" + i;
            new Thread(() -> {
                ClienteCamel cliente = new ClienteCamel(idCliente);
                cliente.setVisible(true);
                cliente.conectarServidor(servidorIp, puertoServidor);
            }).start();

            // Pequeña pausa para evitar conflicto de GUI al iniciar casi simultáneamente
            try { Thread.sleep(500); } catch (InterruptedException e) {}
        }
    }
}