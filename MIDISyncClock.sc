
// a "clock" that slaves itself to midi clock messages
// only one port can be the midi source; hence this is a singleton
// H. James Harkins -- jamshark70@dewdrop-world.net

MIDISyncClock {
	classvar	<>ticksPerBeat = 24;	// MIDI clock standard
	classvar	responseFuncs;

	classvar	<ticks, <beats, <startTime,
	<tempo, <beatDur,
	<beatsPerBar = 4, <barsPerBeat = 0.25, <baseBar, <baseBarBeat;

	// private vars
	classvar	lastTickTime, <queue, medianRoutine, medianSize = 7;

	*initClass {
		responseFuncs = IdentityDictionary[
			// tick
			8 -> { |data|
				var	lastTickDelta, lastQueueTime, nextTime, task, tickIndex;
				var saveClock;
				// use nextTime as temp var to calculate tempo
				// this is inherently inaccurate; tempo will fluctuate slightly around base
				nextTime = SystemClock.seconds;
				lastTickDelta = nextTime - (lastTickTime ? 0);
				lastTickTime = nextTime;
				beatDur = medianRoutine.next(lastTickDelta) * ticksPerBeat;
				tempo = beatDur.reciprocal;

				ticks = ticks + 1;
				beats = ticks / ticksPerBeat;

				saveClock = thisThread.clock;  // "should" be SystemClock
				// while loop needed because more than one thing may be scheduled for this tick
				while {
					lastQueueTime = queue.topPriority;
					// if nil, queue is empty
					lastQueueTime.notNil and: { lastQueueTime <= ticks }
				} {
					// perform the action, and check if it should be rescheduled
					task = queue.pop;
					thisThread.clock = this;
					protect {
						nextTime = task.awake(lastQueueTime / ticksPerBeat, this.seconds, this);
						if(nextTime.isNumber) {
							this.sched(nextTime, task, 0)
						};
					} {
						thisThread.clock = saveClock;
					};
				};
			},
			// start -- scheduler should be clear first
			10 -> { |data|
				startTime = lastTickTime = Main.elapsedTime;
				beats = baseBar = baseBarBeat = 0;
				ticks = -1;  // because we expect a clock message to come next, should be 0
			},
			// stop
			12 -> { |data|
				this.clear;
			}
		];
	}

	*init {
		// retrieve MIDI sources first
		// assumes sources[0] is the MIDI clock source
		// if not, you should init midiclient yourself and manually
		// assign the right port to inport == 0
		// using MIDIIn.connect(0, MIDIClient.sources[x])
		MIDIClient.initialized.not.if({
			MIDIClient.init;
			MIDIClient.sources.do({ arg src, i;
				MIDIIn.connect(i, src);		// connect it
			});
		});
		MIDIIn.sysrt = { |src, index, data| MIDISyncClock.tick(index, data) };
		queue = PriorityQueue.new;
		beats = ticks = baseBar = baseBarBeat = 0;
		medianRoutine = Routine { |inval|
			var i, mid = medianSize div: 2,
			values = Array(medianSize), order = Array(medianSize);
			loop {
				// if arrays are full, drop oldest
				if(values.size == medianSize) {
					i = order.minIndex;
					values.removeAt(i);
					order.removeAt(i);
					order.size.do { |i| order[i] = order[i] - 1 };  // in place, avoid GC load
				};
				i = values.detectIndex { |item| item >= inval };
				if(i.isNil) {
					values = values.add(inval);
					order = order.add(values.size);
				} {
					values = values.insert(i, inval);
					order = order.insert(i, values.size);
				};
				if(values.size < medianSize) {
					inval = values.blendAt((values.size - 1) * 0.5).yield;
				} {
					inval = values[mid].yield;  // optimized, when it's full
				};
			};
		};
	}

	*schedAbs { arg when, task;
		queue.put(when * ticksPerBeat, task);
	}

	*sched { arg when, task, adjustment = 0;
		queue.put((when * ticksPerBeat) + ticks + adjustment, task);
	}

	*tick { |index, data|
		responseFuncs[index].value(data);
	}

	*play { arg task, when;
		when = when.nextTimeOnGrid(this);
		if(when.notNil) {
			this.schedAbs(when, task);
		};
	}

	*nextTimeOnGrid { arg quant = 1, phase = 0;
		var offset;
		if (quant < 0) { quant = beatsPerBar * quant.neg };
		offset = baseBarBeat + phase;
		^roundUp(this.beats - offset, quant) + offset;
	}

	*timeToNextBeat { |quant = 1|
		^quant.nextTimeOnGrid(this) - this.beats
	}

	*beatsPerBar_ { |newBeatsPerBar = 4|
		this.setMeterAtBeat(newBeatsPerBar, beats)
	}

	*setMeterAtBeat { arg newBeatsPerBar, beats;
		// bar must be integer valued when meter changes or confusion results later.
		baseBar = round((beats - baseBarBeat) * barsPerBeat + baseBar, 1);
		baseBarBeat = beats;
		beatsPerBar = newBeatsPerBar;
		barsPerBeat = beatsPerBar.reciprocal;
		this.changed;
	}

	*beats2secs { |beats|
		^beats * beatDur;
	}

	*secs2beats { |seconds|
		^beats
		// A bit of a dodge here. This might break something.
		// But 'tempo' is unstable so the following might lurch forward and back,
		// causing even worse problems.
		// ^seconds * tempo;
		// So we will support the normal case: a stable, increasing time base.
		// This works for patterns but it might f*** up code that expects to coordinate
		// multiple clocks by converting all of their beats to seconds.
	}

	// elapsed time doesn't make sense because this clock only advances when told
	// from outside - but, -play methods need elapsedBeats to calculate quant
	*elapsedBeats { ^beats }
	*seconds { ^startTime.notNil.if(Main.elapsedTime - startTime, nil) }

	*clear { queue.clear }

	// for debugging
	*dumpQueue {
		{ queue.topPriority.notNil }.while({
			Post << "\n" << queue.topPriority << "\n";
			queue.pop.dumpFromQueue;
		});
	}
}
