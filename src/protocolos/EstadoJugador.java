package protocolos;

import java.io.Serializable;

public class EstadoJugador implements Serializable {
    private static final long serialVersionUID = 1L;
    public String idCliente;
    public boolean listo;

    public EstadoJugador(String idCliente, boolean listo) {
        this.idCliente = idCliente;
        this.listo = listo;
    }
}
