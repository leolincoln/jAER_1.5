/*
 * EventGetter.java
 *
 * Created on Feb 24, 2015, 6:59 AM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 *
 *
 * Copyright July 7, 2006 Tobi Delbruck, Inst. of Neuroinformatics, UNI-ETH Zurich
 */
package net.sf.jaer.eventprocessing.filter;

import java.util.Iterator;
import java.util.Observable;
import java.util.Observer;

import net.sf.jaer.Description;
import net.sf.jaer.DevelopmentStatus;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.ApsDvsEventPacket;
import net.sf.jaer.event.BasicEvent;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.eventprocessing.EventFilter2D;

/**
 * Transforms the events in various ways,
 * e.g. rotates the events so that x becomes y and y becomes x.
 * This filter acts on events in-place in the packet so it should be rather fast
 * because it doesn't need to copy events, only modify them.
 *
 * @author Liu
 */
@Description("Testing class: Getting the addresses")
@DevelopmentStatus(DevelopmentStatus.Status.InDevelopment)
public class EventGetter extends EventFilter2D implements Observer {
	private boolean printEvents = getBoolean("printEvents", false);
	private int sx, sy;

	/** Creates a new instance of RotateFilter */
	public EventGetter(AEChip chip) {
		super(chip);
		setPropertyTooltip("printEvents", "print x,y,timestamp for all events");
		sx = chip.getSizeX();
		sy = chip.getSizeY();
		chip.addObserver(this); // to update chip size parameters
	}

	@Override
	public EventPacket<?> filterPacket(EventPacket<?> in) {
		short tmp;
		final int sx2 = sx / 2, sy2 = sy / 2;
		Iterator itr;
		if (in instanceof ApsDvsEventPacket) {
			itr = ((ApsDvsEventPacket) in).fullIterator();
		}
		else {
			itr = in.iterator();
		}
		//System.out.println("the printevent is: " + printEvents);
		while (itr.hasNext()) {
			Object o = itr.next();
			BasicEvent e = (BasicEvent) o;
			if (printEvents) {
				System.out.println("x: " + e.x + " y: " + e.y + " timeStamp: " + e.timestamp);
			}
		}
		return in;
	}

	public Object getFilterState() {
		return null;
	}

	@Override
	public void resetFilter() {
	}

	@Override
	public void initFilter() {
	}

	public boolean isPrintEvents() {
		return printEvents;
	}

	public void setPrintEvents(boolean printEvents) {
		this.printEvents = printEvents;
		putBoolean("printEvents", printEvents);
	}

	@Override
	public void update(Observable o, Object arg) {
		sx = chip.getSizeX();
		sy = chip.getSizeY();
	}

}
