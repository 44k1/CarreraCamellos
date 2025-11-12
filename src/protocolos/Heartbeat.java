package protocolos;

import java.io.Serializable;

public class Heartbeat implements Serializable {
    private static final long serialVersionUID = 1L;
    public String idCliente;
    public long tMarca;

    public Heartbeat(String idCliente, long tMarca) {
        this.idCliente = idCliente;
        this.tMarca = tMarca;
    }
}
