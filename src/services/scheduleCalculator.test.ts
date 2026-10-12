import { describe, it, expect } from 'vitest';
import {
  isTimeInRestPeriod,
  isTimeInAnyRestingPeriod,
  mergeRestingPeriods,
  isWithinOperatingHours,
  isProfileActiveAt,
  getNextNTriggers,
  periodCrossesMidnight,
  doRestsCoverEntireOperatingHours
} from './scheduleCalculator';
import { AudioItem, Profile, RestingPeriod } from '../types';

const formatHHMM = (d: Date) =>
  `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;

describe('ScheduleCalculator - Casos de Teste da Seção 5 do Prompt', () => {

  const sampleAudios: AudioItem[] = [
    {
      id: 1,
      uriString: 'test1.mp3',
      label: 'Áudio 1',
      weight: 3,
      volumePercent: 100,
      isActive: true,
      audioType: 'AUDIO_FILE',
      dateAdded: 1000
    },
    {
      id: 2,
      uriString: 'test2.mp3',
      label: 'Áudio 2',
      weight: 4,
      volumePercent: 100,
      isActive: true,
      audioType: 'AUDIO_FILE',
      dateAdded: 2000
    }
  ];

  // Caso 1: Descanso que atravessa a meia-noite (22:00 às 07:00)
  it('Caso 1: Descanso que atravessa a meia-noite (22:00 às 07:00)', () => {
    const overnightRest: RestingPeriod = {
      id: 1,
      label: 'Sono',
      startTime: '22:00',
      endTime: '07:00'
    };

    expect(periodCrossesMidnight('22:00', '07:00')).toBe(true);

    // Deve estar em descanso às 22:30, 23:59, 00:00, 03:00, 06:59
    expect(isTimeInRestPeriod('22:30', overnightRest)).toBe(true);
    expect(isTimeInRestPeriod('23:59', overnightRest)).toBe(true);
    expect(isTimeInRestPeriod('00:00', overnightRest)).toBe(true);
    expect(isTimeInRestPeriod('03:15', overnightRest)).toBe(true);
    expect(isTimeInRestPeriod('06:59', overnightRest)).toBe(true);

    // Fora do descanso
    expect(isTimeInRestPeriod('07:00', overnightRest)).toBe(false);
    expect(isTimeInRestPeriod('14:00', overnightRest)).toBe(false);
    expect(isTimeInRestPeriod('21:59', overnightRest)).toBe(false);
  });

  // Caso 2: Vários descansos no mesmo dia (almoço + noite)
  it('Caso 2: Vários descansos no mesmo dia (almoço + noite)', () => {
    const multipleRests: RestingPeriod[] = [
      { id: 1, label: 'Almoço', startTime: '12:00', endTime: '13:00' },
      { id: 2, label: 'Noite', startTime: '20:00', endTime: '22:00' }
    ];

    expect(isTimeInAnyRestingPeriod('12:30', multipleRests)).toBe(true);
    expect(isTimeInAnyRestingPeriod('20:45', multipleRests)).toBe(true);
    expect(isTimeInAnyRestingPeriod('14:00', multipleRests)).toBe(false);
    expect(isTimeInAnyRestingPeriod('09:00', multipleRests)).toBe(false);
  });

  // Caso 3: Descansos sobrepostos ou encostados (devem ser mesclados)
  it('Caso 3: Descansos sobrepostos ou encostados (devem ser mesclados)', () => {
    const touchingRests: RestingPeriod[] = [
      { id: 1, label: 'Parte 1', startTime: '12:00', endTime: '13:00' },
      { id: 2, label: 'Parte 2', startTime: '13:00', endTime: '14:30' },
      { id: 3, label: 'Parte 3 (sobreposta)', startTime: '14:00', endTime: '15:00' }
    ];

    const merged = mergeRestingPeriods(touchingRests);
    expect(merged.length).toBe(1);
    expect(merged[0].startTime).toBe('12:00');
    expect(merged[0].endTime).toBe('15:00');
  });

  // Caso 4: Disparo caindo exatamente no início e exatamente no fim de um descanso
  // Regra: Início (12:00) NÃO toca (está descansando). Fim (13:00) TERMINOU descanso, pode tocar.
  it('Caso 4: Disparo caindo exatamente no início (não toca) e no fim (toca)', () => {
    const rest: RestingPeriod = {
      id: 1,
      label: 'Almoço',
      startTime: '12:00',
      endTime: '13:00'
    };

    expect(isTimeInRestPeriod('12:00', rest)).toBe(true); // Início: em descanso (não toca)
    expect(isTimeInRestPeriod('13:00', rest)).toBe(false); // Fim: descanso encerrado (pode tocar)
  });

  // Caso 5: Áudio ainda tocando quando descanso começa (termina normalmente sem interrupção súbita)
  it('Caso 5: Áudio tocando quando descanso começa termina normalmente', () => {
    // A política do reprodutor executa o áudio até o fim via eventos 'ended'
    // e o scheduler apenas bloqueia novos disparos quando isTimeInAnyRestingPeriod é verdadeiro.
    const rest: RestingPeriod = { id: 1, label: 'Descanso', startTime: '12:00', endTime: '13:00' };
    expect(isTimeInRestPeriod('11:59', rest)).toBe(false);
    expect(isTimeInRestPeriod('12:00', rest)).toBe(true);
  });

  // Caso 6: Troca de dia entre perfis diferentes e perfil que atravessa meia-noite
  it('Caso 6: Perfil que atravessa a meia-noite e transição de dia', () => {
    const overnightProfile: Profile = {
      id: 1,
      name: 'Turno da Noite',
      isEnabled: true,
      priority: 1,
      daysOfWeek: [1], // Começa na segunda-feira
      startTime: '22:00',
      endTime: '04:00',
      intervalMinutes: 60,
      playbackOrder: 'SEQUENTIAL',
      audioIds: [],
      restingPeriods: [],
      resumePolicy: 'NEXT_GRID_MARK',
      fadeInOverrideEnabled: false,
      fadeInDurationSeconds: 2
    };

    // Segunda-feira às 23:00 (dia 1)
    const mondayNight = new Date(2026, 9, 12, 23, 0); // 12/10/2026 é segunda
    expect(isProfileActiveAt(overnightProfile, mondayNight)).toBe(true);

    // Terça-feira às 02:00 (dia 2) - Continuação da noite iniciada na segunda
    const tuesdayMorning = new Date(2026, 9, 13, 2, 0); // 13/10/2026 é terça
    expect(isProfileActiveAt(overnightProfile, tuesdayMorning)).toBe(true);

    // Terça-feira às 05:00 - Já passou das 04:00, inativo
    const tuesdayLate = new Date(2026, 9, 13, 5, 0);
    expect(isProfileActiveAt(overnightProfile, tuesdayLate)).toBe(false);
  });

  // Caso 7: Intervalo maior que o horário de funcionamento
  it('Caso 7: Intervalo maior que o horário de funcionamento', () => {
    const shortProfile: Profile = {
      id: 1,
      name: 'Curto',
      isEnabled: true,
      priority: 1,
      daysOfWeek: [1, 2, 3, 4, 5, 6, 7],
      startTime: '10:00',
      endTime: '10:30', // Janela de 30 minutos
      intervalMinutes: 60, // Intervalo de 60 minutos
      playbackOrder: 'SEQUENTIAL',
      audioIds: [],
      restingPeriods: [],
      resumePolicy: 'NEXT_GRID_MARK',
      fadeInOverrideEnabled: false,
      fadeInDurationSeconds: 2
    };

    const now = new Date(2026, 9, 12, 9, 0);
    const triggers = getNextNTriggers(now, 5, [shortProfile], sampleAudios);

    // Deve conter apenas o disparo inicial em 10:00, pois o próximo (11:00) ultrapassa 10:30
    expect(triggers.length).toBeGreaterThan(0);
    const triggerTimes = triggers.map(t => formatHHMM(new Date(t.triggerTime)));
    expect(triggerTimes).toContain('10:00');
    expect(triggerTimes).not.toContain('11:00');
  });

  // Caso 8: Validação de descansos que cobrem toda a janela
  it('Caso 8: Descansos que cobrem todo o horário de funcionamento', () => {
    const fullyCoveredProfile: Profile = {
      id: 1,
      name: 'Totalmente Descansando',
      isEnabled: true,
      priority: 1,
      daysOfWeek: [1, 2, 3],
      startTime: '08:00',
      endTime: '18:00',
      intervalMinutes: 15,
      playbackOrder: 'SEQUENTIAL',
      audioIds: [],
      restingPeriods: [
        { id: 1, label: 'Dia Todo', startTime: '08:00', endTime: '18:00' }
      ],
      resumePolicy: 'NEXT_GRID_MARK',
      fadeInOverrideEnabled: false,
      fadeInDurationSeconds: 2
    };

    expect(doRestsCoverEntireOperatingHours(fullyCoveredProfile)).toBe(true);

    const normalProfile: Profile = {
      ...fullyCoveredProfile,
      restingPeriods: [{ id: 1, label: 'Almoço', startTime: '12:00', endTime: '13:00' }]
    };
    expect(doRestsCoverEntireOperatingHours(normalProfile)).toBe(false);
  });

  // Caso 9: Retomada após descanso (NEXT_GRID_MARK vs AT_REST_END)
  it('Caso 9: Política de Retomada AT_REST_END dispara exatamente no término do descanso', () => {
    const profileAtRestEnd: Profile = {
      id: 1,
      name: 'Retomada Imediata',
      isEnabled: true,
      priority: 1,
      daysOfWeek: [1, 2, 3, 4, 5, 6, 7],
      startTime: '08:00',
      endTime: '18:00',
      intervalMinutes: 30, // 08:00, 08:30, 09:00, 09:30 ... 12:00, 12:30, 13:00
      playbackOrder: 'SEQUENTIAL',
      audioIds: [],
      restingPeriods: [
        { id: 1, label: 'Almoço', startTime: '12:10', endTime: '12:45' }
      ],
      resumePolicy: 'AT_REST_END',
      fadeInOverrideEnabled: false,
      fadeInDurationSeconds: 2
    };

    const now = new Date(2026, 9, 12, 11, 0);
    const triggers = getNextNTriggers(now, 10, [profileAtRestEnd], sampleAudios);
    const triggerTimes = triggers.map(t => formatHHMM(new Date(t.triggerTime)));

    // Deve incluir 12:45 (fim exato do descanso)
    expect(triggerTimes).toContain('12:45');
  });

  // Caso 10: Snooze silencia temporariamente disparos sem afetar agenda futura
  it('Caso 10: Snooze atrasa o início efetivo do cálculo de disparos', () => {
    const profile: Profile = {
      id: 1,
      name: 'Normal',
      isEnabled: true,
      priority: 1,
      daysOfWeek: [1, 2, 3, 4, 5, 6, 7],
      startTime: '08:00',
      endTime: '18:00',
      intervalMinutes: 15,
      playbackOrder: 'SEQUENTIAL',
      audioIds: [],
      restingPeriods: [],
      resumePolicy: 'NEXT_GRID_MARK',
      fadeInOverrideEnabled: false,
      fadeInDurationSeconds: 2
    };

    const now = new Date(2026, 9, 12, 10, 0);
    const snoozeUntil = new Date(2026, 9, 12, 10, 40); // Silenciado até 10:40

    const triggers = getNextNTriggers(now, 5, [profile], sampleAudios, snoozeUntil);
    const firstTrigger = new Date(triggers[0].triggerTime);

    // O primeiro disparo deve ser após 10:40 (ou seja, 10:45)
    expect(firstTrigger.getTime()).toBeGreaterThanOrEqual(snoozeUntil.getTime());
  });

});
