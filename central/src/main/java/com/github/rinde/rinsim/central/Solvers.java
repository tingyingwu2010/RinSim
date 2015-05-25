/*
 * Copyright (C) 2011-2015 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.rinsim.central;

import static com.github.rinde.rinsim.core.model.pdp.PDPModel.ParcelState.ANNOUNCED;
import static com.github.rinde.rinsim.core.model.pdp.PDPModel.ParcelState.AVAILABLE;
import static com.github.rinde.rinsim.core.model.pdp.PDPModel.ParcelState.PICKING_UP;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.newLinkedList;
import static com.google.common.collect.Sets.newHashSet;

import java.math.RoundingMode;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import javax.annotation.Nullable;
import javax.measure.Measure;
import javax.measure.quantity.Duration;
import javax.measure.quantity.Length;
import javax.measure.quantity.Velocity;
import javax.measure.unit.Unit;

import com.github.rinde.rinsim.central.GlobalStateObject.VehicleStateObject;
import com.github.rinde.rinsim.core.Simulator;
import com.github.rinde.rinsim.core.SimulatorAPI;
import com.github.rinde.rinsim.core.model.ModelProvider;
import com.github.rinde.rinsim.core.model.pdp.PDPModel;
import com.github.rinde.rinsim.core.model.pdp.PDPModel.VehicleParcelActionInfo;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.core.model.road.RoadModels;
import com.github.rinde.rinsim.core.model.time.Clock;
import com.github.rinde.rinsim.core.model.time.TimeModel;
import com.github.rinde.rinsim.core.pdptw.DefaultVehicle;
import com.github.rinde.rinsim.core.pdptw.VehicleDTO;
import com.github.rinde.rinsim.geom.Point;
import com.github.rinde.rinsim.pdptw.common.PDPRoadModel;
import com.github.rinde.rinsim.pdptw.common.StatisticsDTO;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.math.DoubleMath;

/**
 * @author Rinde van Lon
 * 
 */
public final class Solvers {

  private Solvers() {}

  /**
   * Creates a builder for creating {@link SimulationSolver} instances. For more
   * information see {@link AdapterBuilder}.
   * @param sol The solver to use internally.
   * @return The builder.
   */
  public static AdapterBuilder<SimulationSolver> solverBuilder(Solver sol) {
    return new AdapterBuilder<>(sol);
  }

  /**
   * Creates a builder for creating {@link SimulationConverter} instances.
   * @return The builder.
   */
  public static AdapterBuilder<SimulationConverter> converterBuilder() {
    return new AdapterBuilder<>(null);
  }

  /**
   * Computes a {@link StatisticsDTO} instance for the given
   * {@link GlobalStateObject} and routes. For each vehicle in the state the
   * specified route is used and its arrival times, tardiness and travel times
   * are computed. The resulting {@link StatisticsDTO} has the same properties
   * as performing a simulation with the same state. However, since the current
   * state may be half-way a simulation, it is possible that the returned
   * statistics describe only a partial simulation. As a result
   * {@link StatisticsDTO#totalDeliveries} does not necessarily equal
   * {@link StatisticsDTO#totalPickups}.
   * @param state The state which represents a simulation.
   * @param routes Specifies the route the vehicles are currently following,
   *          must be of same size as the number of vehicles (one route per
   *          vehicle). If this is <code>null</code> the
   *          {@link VehicleStateObject#route} field must be set instead for
   *          <b>each</b> vehicle.
   * @return The statistics that will be generated when executing this
   *         simulation.
   */
  public static StatisticsDTO computeStats(GlobalStateObject state,
    @Nullable ImmutableList<ImmutableList<Parcel>> routes) {
    final Optional<ImmutableList<ImmutableList<Parcel>>> r = Optional
      .fromNullable(routes);

    if (r.isPresent()) {
      checkArgument(
        state.vehicles.size() == r.get().size(),
        "Exactly one route should be supplied for every vehicle in state. %s vehicle(s) in state, received %s route(s).",
        state.vehicles.size(), r.get().size());
    }

    double totalDistance = 0;
    int totalDeliveries = 0;
    int totalPickups = 0;
    long pickupTardiness = 0;
    long deliveryTardiness = 0;
    long overTime = 0;
    final long startTime = state.time;
    long maxTime = 0;
    int movedVehicles = 0;
    final Set<Parcel> parcels = newHashSet();

    final ImmutableList.Builder<ImmutableList<Long>> arrivalTimesBuilder = ImmutableList
      .builder();

    for (int i = 0; i < state.vehicles.size(); i++) {
      final VehicleStateObject vso = state.vehicles.get(i);
      checkArgument(r.isPresent() || vso.route.isPresent());

      final ImmutableList.Builder<Long> truckArrivalTimesBuilder = ImmutableList
        .builder();
      truckArrivalTimesBuilder.add(state.time);

      ImmutableList<Parcel> route;
      if (r.isPresent()) {
        route = r.get().get(i);
      } else {
        route = vso.route.get();
      }
      parcels.addAll(route);

      long time = state.time;
      Point vehicleLocation = vso.location;
      final Measure<Double, Velocity> speed = Measure.valueOf(
        vso.getDto().speed, state.speedUnit);
      final Set<Parcel> seen = newHashSet();
      for (int j = 0; j < route.size(); j++) {
        final Parcel cur = route.get(j);
        final boolean inCargo = vso.contents.contains(cur)
          || seen.contains(cur);
        seen.add(cur);
        if (vso.destination != null && j == 0) {
          checkArgument(
            vso.destination == cur,
            "If a vehicle has a destination, the first position in the route must equal this. Expected %s, is %s.",
            vso.destination, cur);
        }

        boolean firstAndServicing = false;
        if (j == 0 && vso.remainingServiceTime > 0) {
          // we are already at the service location
          firstAndServicing = true;
          truckArrivalTimesBuilder.add(time);
          time += vso.remainingServiceTime;
        } else {
          // vehicle is not there yet, go there first, then service
          final Point nextLoc = inCargo ? cur.getDeliveryLocation()
            : cur.getPickupLocation();
          final Measure<Double, Length> distance = Measure.valueOf(
            Point.distance(vehicleLocation, nextLoc), state.distUnit);
          totalDistance += distance.getValue();
          vehicleLocation = nextLoc;
          final long tt = DoubleMath.roundToLong(
            RoadModels.computeTravelTime(speed, distance, state.timeUnit),
            RoundingMode.CEILING);
          time += tt;
        }
        if (inCargo) {
          // check if we are early
          if (cur.getDeliveryTimeWindow().isBeforeStart(time)) {
            time = cur.getDeliveryTimeWindow().begin;
          }

          if (!firstAndServicing) {
            truckArrivalTimesBuilder.add(time);
            time += cur.getDeliveryDuration();
          }
          // delivering
          if (cur.getDeliveryTimeWindow().isAfterEnd(time)) {
            final long tardiness = time - cur.getDeliveryTimeWindow().end;
            deliveryTardiness += tardiness;
          }
          totalDeliveries++;
        } else {
          // check if we are early
          if (cur.getPickupTimeWindow().isBeforeStart(time)) {
            time = cur.getPickupTimeWindow().begin;
          }
          if (!firstAndServicing) {
            truckArrivalTimesBuilder.add(time);
            time += cur.getPickupDuration();
          }
          // picking up
          if (cur.getPickupTimeWindow().isAfterEnd(time)) {
            final long tardiness = time - cur.getPickupTimeWindow().end;
            pickupTardiness += tardiness;
          }
          totalPickups++;
        }
      }

      // go to depot
      final Measure<Double, Length> distance = Measure.valueOf(
        Point.distance(vehicleLocation, vso.getDto().startPosition),
        state.distUnit);
      totalDistance += distance.getValue();
      final long tt = DoubleMath.roundToLong(
        RoadModels.computeTravelTime(speed, distance, state.timeUnit),
        RoundingMode.CEILING);
      time += tt;
      // check overtime
      if (vso.getDto().availabilityTimeWindow.isAfterEnd(time)) {
        overTime += time - vso.getDto().availabilityTimeWindow.end;
      }
      maxTime = Math.max(maxTime, time);

      truckArrivalTimesBuilder.add(time);
      arrivalTimesBuilder.add(truckArrivalTimesBuilder.build());

      if (time > startTime) {
        // time has progressed -> the vehicle has moved
        movedVehicles++;
      }
    }
    final int totalParcels = parcels.size();
    final int totalVehicles = state.vehicles.size();
    final long simulationTime = maxTime - startTime;

    return new ExtendedStats(totalDistance, totalPickups, totalDeliveries,
      totalParcels, totalParcels, pickupTardiness, deliveryTardiness, 0,
      simulationTime, true, totalVehicles, overTime, totalVehicles,
      movedVehicles, state.timeUnit, state.distUnit, state.speedUnit,
      arrivalTimesBuilder.build());
  }

  /**
   * Converts the specified collection containing {@link Parcel}s into an
   * {@link ImmutableList} of {@link Parcel}s.
   * @param parcels The parcels to convert.
   * @return A list of {@link Parcel}s in the same order as in the provided
   *         collection.
   */
  // public static ImmutableList<Parcel> toDtoList(
  // Collection<Parcel> parcels) {
  // final ImmutableList.Builder<Parcel> builder = ImmutableList.builder();
  // for (final Parcel dp : parcels) {
  // builder.add(dp.getDto());
  // }
  // return builder.build();
  // }

  /**
   * Converts the specified collection of collections containing
   * {@link DefaultParcel}s into a list of lists containing {@link Parcel}s.
   * @param routes The collection of collections of parcels to convert.
   * @return A list of lists of {@link Parcel}s in the same order as the
   *         provided lists.
   */
  // public static ImmutableList<ImmutableList<Parcel>> toDtoLists(
  // Collection<? extends Collection<Parcel>> routes) {
  // final ImmutableList.Builder<ImmutableList<Parcel>> newRoutes =
  // ImmutableList
  // .builder();
  // for (final Collection<Parcel> route : routes) {
  // newRoutes.add(toDtoList(route));
  // }
  // return newRoutes.build();
  // }

  // converts the routes received from Solver.solve(..) into a format which is
  // expected by the simulator
  static ImmutableList<Queue<Parcel>> convertRoutes(StateContext cont,
    List<? extends List<Parcel>> routes) {
    final ImmutableList.Builder<Queue<Parcel>> routesBuilder = ImmutableList
      .builder();
    for (final List<Parcel> route : routes) {
      routesBuilder.add(newLinkedList(route));
    }
    return routesBuilder.build();
  }

  static StateContext convert(PDPRoadModel rm, PDPModel pm,
    Collection<DefaultVehicle> vehicles,
    Set<Parcel> availableParcels, Measure<Long, Duration> time,
    Optional<ImmutableList<ImmutableList<Parcel>>> currentRoutes) {

    // final ImmutableList.Builder<VehicleStateObject> vbuilder = ImmutableList
    // .builder();
    final ImmutableMap.Builder<VehicleStateObject, DefaultVehicle> vbuilder = ImmutableMap
      .builder();

    @Nullable
    Iterator<ImmutableList<Parcel>> routeIterator = null;
    if (currentRoutes.isPresent()) {
      checkArgument(currentRoutes.get().size() == vehicles.size(),
        "The number of routes (%s) must equal the number of vehicles (%s).",
        currentRoutes.get().size(), vehicles.size());
      routeIterator = currentRoutes.get().iterator();
    }

    final ImmutableSet.Builder<Parcel> availableDestParcels = ImmutableSet
      .builder();
    for (final DefaultVehicle v : vehicles) {
      final ImmutableSet<Parcel> contentsMap = ImmutableSet.copyOf(pm
        .getContents(v));

      @Nullable
      ImmutableList<Parcel> route = null;
      if (routeIterator != null) {
        route = routeIterator.next();
      }

      final VehicleStateObject vehicleState = convertToVehicleState(rm, pm, v,
        contentsMap, route, availableDestParcels);

      vbuilder.put(vehicleState, v);
    }

    // final ImmutableMap<Parcel, Parcel> availableMap =
    // toMap(availableParcels);
    final ImmutableSet<Parcel> availableDestMap = availableDestParcels.build();

    final Set<Parcel> toAdd = Sets.difference(availableParcels,
      availableDestMap);// .entriesOnlyOnRight();

    final ImmutableSet<Parcel> availableParcelsKeys = ImmutableSet
      .<Parcel> builder()
      .addAll(availableParcels)
      .addAll(toAdd)
      .build();

    final ImmutableMap<VehicleStateObject, DefaultVehicle> vehicleMap = vbuilder
      .build();

    return new StateContext(new GlobalStateObject(availableParcelsKeys,
      vehicleMap.keySet().asList(), time.getValue().longValue(),
      time.getUnit(), rm.getSpeedUnit(), rm.getDistanceUnit()), vehicleMap);
  }

  // static ImmutableSet<Parcel> contentsToMap(PDPModel pm,
  // DefaultVehicle vehicle) {
  // // this is ok since we actually check the type
  // // @SuppressWarnings({ "unchecked", "rawtypes" })
  //
  // return ImmutableSet.copyOf(pm.getContents(vehicle));
  // // final Set<Parcel> ps = Collections.checkedSet(
  // // newLinkedHashSet(pm.getContents(vehicle)), Parcel.class);
  // // return toMap(ps);
  // }

  // TODO check for bugs
  static VehicleStateObject convertToVehicleState(PDPRoadModel rm, PDPModel pm,
    DefaultVehicle vehicle, ImmutableSet<Parcel> contents,
    @Nullable ImmutableList<Parcel> route,
    ImmutableSet.Builder<Parcel> availableDestBuilder) {
    final boolean isIdle = pm.getVehicleState(vehicle) == PDPModel.VehicleState.IDLE;

    long remainingServiceTime = 0;
    @Nullable
    Parcel destination = null;
    if (!isIdle) {
      final VehicleParcelActionInfo vpai = pm.getVehicleActionInfo(vehicle);
      destination = vpai.getParcel();
      remainingServiceTime = vpai.timeNeeded();
    } else if (!rm.isVehicleDiversionAllowed()) {
      // check whether the vehicle is already underway to parcel
      destination = rm.getDestinationToParcel(vehicle);
    }

    // destinations which are not yet picked up should be put in the builder
    if (destination != null && !pm.getParcelState(destination).isPickedUp()) {
      availableDestBuilder.add(destination);
    }

    return new VehicleStateObject(vehicle.getDTO(), rm.getPosition(vehicle),
      contents, remainingServiceTime, destination == null ? null
        : destination, route);
  }

  // static ImmutableMap<Parcel, Parcel> toMap(
  // Collection<Parcel> parcels) {
  // final ImmutableMap.Builder<Parcel, Parcel> parcelMapBuilder = ImmutableMap
  // .builder();
  // for (final Parcel dp : parcels) {
  // parcelMapBuilder.put(dp.getDto(), dp);
  // }
  // return parcelMapBuilder.build();
  // }

  /**
   * Converter that converts simulations into {@link StateContext} instances
   * which are needed to call {@link Solver#solve(GlobalStateObject)}.
   * @author Rinde van Lon
   */
  public interface SimulationConverter {
    /**
     * Converts the simulation into a {@link StateContext} object.
     * @param args {@link SolveArgs}.
     * @return {@link StateContext}.
     */
    StateContext convert(SolveArgs args);
  }

  /**
   * Builder for specifying parameters used in {@link SimulationSolver} and
   * {@link SimulationConverter}.
   * @author Rinde van Lon
   */
  public static final class SolveArgs {
    Optional<ImmutableSet<Parcel>> parcels;
    Optional<ImmutableList<ImmutableList<Parcel>>> currentRoutes;

    private SolveArgs() {
      parcels = Optional.absent();
      currentRoutes = Optional.absent();
    }

    /**
     * @return {@link SolveArgs} builder.
     */
    public static SolveArgs create() {
      return new SolveArgs();
    }

    /**
     * Indicates that receivers of this object should use all parcels it knows.
     * @return This, as per the builder pattern.
     */
    public SolveArgs useAllParcels() {
      parcels = Optional.absent();
      return this;
    }

    /**
     * Indicates that receivers of this object should use only the parcels that
     * are specified.
     * @param ps The parcels to use.
     * @return This, as per the builder pattern.
     */
    public SolveArgs useParcels(Iterable<? extends Parcel> ps) {
      parcels = Optional.of(ImmutableSet.copyOf(ps));
      return this;
    }

    /**
     * Indicates that receivers of this object should use no current routes for
     * the vehicles it knows about.
     * @return This, as per the builder pattern.
     */
    public SolveArgs noCurrentRoutes() {
      currentRoutes = Optional.absent();
      return this;
    }

    /**
     * Indicates that receivers of this object should use the specified current
     * routes for the vehicles it knows about. The number of specified route
     * needs to match the number of known vehicles.
     * @param cr The current routes to use.
     * @return This, as per the builder pattern.
     */
    public SolveArgs useCurrentRoutes(
      ImmutableList<ImmutableList<Parcel>> cr) {
      currentRoutes = Optional.of(cr);
      return this;
    }
  }

  /**
   * Adapter for {@link Solver}s.
   * @author Rinde van Lon
   */
  public static class SimulationSolver implements SimulationConverter {
    final Optional<Solver> solver;
    final Clock clock;
    final PDPRoadModel roadModel;
    final PDPModel pdpModel;
    final List<DefaultVehicle> vehicles;

    SimulationSolver(Optional<Solver> s, PDPRoadModel rm, PDPModel pm,
      Clock sim, List<DefaultVehicle> vs) {
      solver = s;
      clock = sim;
      roadModel = rm;
      pdpModel = pm;
      vehicles = vs;
    }

    /**
     * Calls the {@link Solver} to solve the problem as defined by the current
     * simulation state.
     * @param args {@link SolveArgs} specifying what information to include.
     * @return A list containing routes for each vehicle known to this solver.
     */
    public List<Queue<Parcel>> solve(SolveArgs args) {
      return solve(convert(args));
    }

    /**
     * Calls the {@link Solver} to solve the problem as defined by the current
     * simulation state.
     * @param state The {@link StateContext} that specifies the current
     *          simulation state.
     * @return A list of routes, one for each vehicle.
     */
    public List<Queue<Parcel>> solve(StateContext state) {
      return Solvers.convertRoutes(state, solver.get().solve(state.state));
    }

    @Override
    public StateContext convert(SolveArgs args) {
      final Collection<DefaultVehicle> vs = vehicles.isEmpty() ? roadModel
        .getObjectsOfType(DefaultVehicle.class) : vehicles;
      final Set<Parcel> ps = args.parcels.isPresent()
        ? args.parcels.get()
        : ImmutableSet.copyOf(pdpModel.getParcels(ANNOUNCED, AVAILABLE,
          PICKING_UP));
      return Solvers.convert(roadModel, pdpModel, vs, ps, time(),
        args.currentRoutes);
    }

    Measure<Long, Duration> time() {
      return Measure.valueOf(clock.getCurrentTime(),
        clock.getTimeUnit());
    }
  }

  /**
   * Builder for creating adapters for {@link Solver}s that need to solve
   * simulation instances. For creating an adapter four different pieces of
   * information are required, each can be supplied to this builder via a
   * variety of methods which are listed below.
   * <ul>
   * <li>{@link PDPRoadModel} - can be supplied directly, via a
   * {@link ModelProvider} or via {@link Simulator} instance</li>
   * <li>{@link PDPModel} - can be supplied directly, via a
   * {@link ModelProvider} or via {@link Simulator} instance</li>
   * <li>{@link SimulatorAPI} - can be supplied directly or via a
   * {@link Simulator} instance.</li>
   * <li>A number of {@link DefaultVehicle}s - can be supplied directly or if
   * not supplied all vehicles available in the {@link PDPRoadModel} instance
   * will be used.</li>
   * </ul>
   * 
   * @param <T> The type of adapter to produce.
   * @author Rinde van Lon
   */
  public static class AdapterBuilder<T extends SimulationConverter> {
    @Nullable
    Simulator simulator;
    @Nullable
    Clock clock;
    @Nullable
    ModelProvider modelProvider;
    @Nullable
    PDPRoadModel roadModel;
    @Nullable
    PDPModel pdpModel;
    final List<DefaultVehicle> vehicles;
    final Optional<Solver> solver;

    AdapterBuilder(@Nullable Solver s) {
      solver = Optional.fromNullable(s);
      vehicles = newArrayList();
    }

    /**
     * @param sim The {@link Simulator} to provide to the adapter.
     * @return This, as per the builder pattern.
     */
    public AdapterBuilder<T> with(Simulator sim) {
      simulator = sim;
      return this;
    }

    /**
     * @param mp The {@link ModelProvider} to use for extracting the models.
     *          Calls to this method take precedence over
     *          {@link #with(Simulator)}.
     * @return This, as per the builder pattern.
     */
    public AdapterBuilder<T> with(ModelProvider mp) {
      modelProvider = mp;
      return this;
    }

    /**
     * @param rm The {@link PDPRoadModel} to use in the adapter. Calls to this
     *          method take precedence over {@link #with(ModelProvider)} and
     *          {@link #with(Simulator)}.
     * @return This, as per the builder pattern.
     */
    public AdapterBuilder<T> with(PDPRoadModel rm) {
      roadModel = rm;
      return this;
    }

    /**
     * @param pm The {@link PDPModel} to use in the adapter. Calls to this
     *          method take precedence over {@link #with(ModelProvider)} and
     *          {@link #with(Simulator)}.
     * @return This, as per the builder pattern.
     */
    public AdapterBuilder<T> with(PDPModel pm) {
      pdpModel = pm;
      return this;
    }

    /**
     * @param c The {@link Clock} to use in the adapter. Calls to this method
     *          take precedence over {@link #with(Simulator)}.
     * @return This, as per the builder pattern.
     */
    public AdapterBuilder<T> with(Clock c) {
      clock = c;
      return this;
    }

    /**
     * Adds the specified vehicle to the resulting adapter, the vehicle will be
     * included in the resulting adapter. When no vehicles are supplied, the
     * adapter will use all vehicles in {@link PDPRoadModel}.
     * @param dv The {@link DefaultVehicle} to add.
     * @return This, as per the builder pattern.
     */
    public AdapterBuilder<T> with(DefaultVehicle dv) {
      vehicles.add(dv);
      return this;
    }

    /**
     * Adds the specified vehicles to the resulting adapter, the vehicles will
     * be included in the resulting adapter. When no vehicles are supplied, the
     * adapter will use all vehicles in {@link PDPRoadModel}.
     * @param dv The {@link DefaultVehicle}s to include.
     * @return This, as per the builder pattern.
     */
    public AdapterBuilder<T> with(List<? extends DefaultVehicle> dv) {
      vehicles.addAll(dv);
      return this;
    }

    /**
     * Builds the adapter.
     * @return The newly created adapter.
     */
    @SuppressWarnings("unchecked")
    public T build() {
      PDPRoadModel rm = roadModel;
      PDPModel pm = pdpModel;
      if (rm == null || pm == null) {
        // in this case we need a model provider
        @Nullable
        ModelProvider mp = modelProvider;
        if (mp == null) {
          checkArgument(
            simulator != null,
            "Attempt to find a model provider failed. Either provide the models"
              + " directly, provide a model provider or a simulator.");
          mp = simulator.getModelProvider();
        }

        if (rm == null) {
          rm = mp.getModel(PDPRoadModel.class);
        }
        if (pm == null) {
          pm = mp.getModel(PDPModel.class);
        }
      }

      Clock c = clock;
      if (c == null && simulator != null) {
        c = simulator.getModelProvider().getModel(TimeModel.class);
      }
      if (c != null && rm != null && pm != null) {
        return (T) new SimulationSolver(solver, rm, pm, c, vehicles);
      }
      throw new IllegalArgumentException(
        "Not all required components could be found, PDPRoadModel: " + rm
          + ", PDPModel: " + pm + ", Clock: " + c);
    }

    /**
     * Builds an adapter which can deal with only one vehicle.
     * @return A new created adapter.
     */
    public T buildSingle() {
      checkArgument(vehicles.size() == 1);
      return build();
    }
  }

  /**
   * Value object containing representing the state of a simulation. It contains
   * a {@link GlobalStateObject} (the actual state) and two maps with references
   * to the original vehicles and parcels. Using these maps the state object can
   * be translated back to the original simulation objects.
   * @author Rinde van Lon
   */
  public static class StateContext {
    /**
     * A reference to the {@link GlobalStateObject}.
     */
    public final GlobalStateObject state;
    /**
     * A mapping of {@link VehicleDTO} to {@link DefaultVehicle}.
     */
    public final ImmutableMap<VehicleStateObject, DefaultVehicle> vehicleMap;

    StateContext(GlobalStateObject state,
      ImmutableMap<VehicleStateObject, DefaultVehicle> vehicleMap) {
      this.state = state;
      this.vehicleMap = vehicleMap;
    }
  }

  // only used for testing
  static class ExtendedStats extends StatisticsDTO {
    private static final long serialVersionUID = 3682772955122186862L;
    final ImmutableList<ImmutableList<Long>> arrivalTimes;

    ExtendedStats(double dist, int pick, int del, int parc, int accP,
      long pickTar, long delTar, long compT, long simT, boolean finish,
      int atDepot, long overT, int total, int moved, Unit<Duration> time,
      Unit<Length> distUnit, Unit<Velocity> speed,
      ImmutableList<ImmutableList<Long>> arrivalTimes) {
      super(dist, pick, del, parc, accP, pickTar, delTar, compT, simT, finish,
        atDepot, overT, total, moved, time, distUnit, speed);
      this.arrivalTimes = arrivalTimes;
    }
  }
}
