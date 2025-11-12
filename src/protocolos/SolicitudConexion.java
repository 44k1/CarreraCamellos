package protocolos;

import java.io.Serializable;

public class SolicitudConexion implements Serializable {
    private static final long serialVersionUID = 1L;
    public String idCliente;

    public SolicitudConexion(String idCliente) {
        this.idCliente = idCliente;
    }
}
