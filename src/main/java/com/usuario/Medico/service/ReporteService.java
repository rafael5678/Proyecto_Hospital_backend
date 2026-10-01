package com.usuario.Medico.service;

import com.usuario.Medico.dto.AiMetricasResponse;
import com.usuario.Medico.dto.ReporteResponse;
import com.usuario.Medico.model.EstadoCita;
import com.usuario.Medico.repository.CitaRepository;
import com.usuario.Medico.repository.CambioCitaRepository;
import com.usuario.Medico.repository.MedicoRepository;
import com.usuario.Medico.repository.PacienteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HU-15: Reportes con recursividad en agregarMesRecursivo.
 */
@Service
@RequiredArgsConstructor
public class ReporteService {

    private final CitaRepository citaRepository;
    private final PacienteRepository pacienteRepository;
    private final MedicoRepository medicoRepository;
    private final CambioCitaRepository cambioCitaRepository;

    public ReporteResponse generarReporte(int anio) {
        List<Map<String, Object>> desglose = new ArrayList<>();
        agregarMesRecursivo(anio, 1, desglose);
        return ReporteResponse.builder()
                .totalCitas(citaRepository.count())
                .citasPendientes(citaRepository.countByEstado(EstadoCita.PENDIENTE))
                .citasAceptadas(citaRepository.countByEstado(EstadoCita.ACEPTADA))
                .citasCanceladas(citaRepository.countByEstado(EstadoCita.CANCELADA))
                .totalPacientes(pacienteRepository.count())
                .totalMedicos(medicoRepository.count())
                .desgloseMensual(desglose)
                .build();
    }

    public AiMetricasResponse generarMetricasIA(boolean apiKeyActiva) {
        long totalCitas = citaRepository.count();
        long citasConTriage = citaRepository.countConTriage();
        long citasConPiel = citaRepository.countConEvaluacionPiel();
        long prioridadAlta = citaRepository.countPrioridadAlta();
        long reprogramadasIA = cambioCitaRepository.countByRealizadoPor("SISTEMA_IA");

        /* Distribución de severidad */
        Map<String, Long> distribucion = new LinkedHashMap<>();
        for (String sev : List.of("Rojo", "Naranja", "Amarillo", "Verde", "Azul")) {
            distribucion.put(sev, 0L);
        }
        for (Object[] row : citaRepository.distribucionSeveridad()) {
            String sev = (String) row[0];
            Long cnt = (Long) row[1];
            distribucion.put(sev, cnt);
        }

        String estadoConexion = apiKeyActiva ? "Operativo — API Key configurada" : "Modo Demo — sin API Key";

        return AiMetricasResponse.builder()
                .totalCitas(totalCitas)
                .citasConTriage(citasConTriage)
                .citasConEvaluacionPiel(citasConPiel)
                .citasPrioridadAlta(prioridadAlta)
                .citasReprogramadasPorIA(reprogramadasIA)
                .distribucionSeveridad(distribucion)
                .apiKeyActiva(apiKeyActiva)
                .versionIa("Hospy AI v2.0 — LLM + Whisper + NER SOAP")
                .estadoWhisper(apiKeyActiva ? "Operativo" : "Demo (navegador Web Speech API)")
                .estadoSoap(apiKeyActiva ? "Operativo — LLM genera borrador SOAP" : "Demo — plantilla local")
                .estadoTriage(apiKeyActiva ? "Operativo — análisis NLP" : "Demo — reglas de palabra clave")
                .estadoDermatologia(apiKeyActiva ? "Operativo — análisis textual LLM" : "Demo — clasificación por texto")
                .estadoInteracciones(apiKeyActiva ? "Operativo — LLM revisa interacciones" : "Demo — reglas predefinidas")
                .estadoPrecios(apiKeyActiva ? "Operativo — estimación LLM" : "Demo — precios simulados")
                .timeoutMedicoSeg(300)
                .timeoutAdminSeg(600)
                .build();
    }

    private void agregarMesRecursivo(int anio, int mes, List<Map<String, Object>> acumulado) {
        if (mes > 12) return;
        YearMonth ym = YearMonth.of(anio, mes);
        LocalDateTime inicio = ym.atDay(1).atStartOfDay();
        LocalDateTime fin = ym.atEndOfMonth().atTime(23, 59, 59);
        long total = citaRepository.countByFechaHoraBetween(inicio, fin);
        Map<String, Object> dato = new HashMap<>();
        dato.put("mes", mes);
        dato.put("nombreMes", obtenerNombreMes(mes));
        dato.put("totalCitas", total);
        acumulado.add(dato);
        agregarMesRecursivo(anio, mes + 1, acumulado);
    }

    private String obtenerNombreMes(int mes) {
        String[] nombres = {"", "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
                "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};
        return nombres[mes];
    }
}
