package protocolos;

import java.io.Serializable;

public class AsignacionGrupo implements Serializable {
    private static final long serialVersionUID = 1L;
    public int idGrupo;
    public String ipMulticast;
    public int puerto;
    public int tamGrupo;
    public long semillaCarrera;

    public AsignacionGrupo(int idGrupo, String ipMulticast, int puerto, int tamGrupo, long semillaCarrera) {
        this.idGrupo = idGrupo;
        this.ipMulticast = ipMulticast;
        this.puerto = puerto;
        this.tamGrupo = tamGrupo;
        this.semillaCarrera = semillaCarrera;
    }
}
