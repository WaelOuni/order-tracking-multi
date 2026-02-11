import { useMemo, useState } from "react";
import type { OrderResponse, OrderStatus } from "./types";
import { getOrderById, listOrders, registerOrder, updateOrderStatus } from "./api/client";

const STATUS_OPTIONS: OrderStatus[] = [
  "CREATED",
  "PACKED",
  "SHIPPED",
  "DELIVERED",
  "CANCELLED"
];

type RequestState<T> = {
  loading: boolean;
  error: string | null;
  data: T | null;
};

type ActionEntry = {
  label: string;
  orderId: string;
  timestamp: string;
  status?: string | null;
};

function useRequestState<T>(): [RequestState<T>, (state: RequestState<T>) => void] {
  const [state, setState] = useState<RequestState<T>>({
    loading: false,
    error: null,
    data: null
  });
  return [state, setState];
}

function formatDate(value?: string | null) {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export default function App() {
  const [registerOrderId, setRegisterOrderId] = useState("");
  const [registerCustomerId, setRegisterCustomerId] = useState("");
  const [trackOrderId, setTrackOrderId] = useState("");
  const [updateOrderId, setUpdateOrderId] = useState("");
  const [updateStatus, setUpdateStatus] = useState<OrderStatus>("PACKED");
  const [updateNote, setUpdateNote] = useState("");

  const [registerState, setRegisterState] = useRequestState<OrderResponse>();
  const [trackState, setTrackState] = useRequestState<OrderResponse>();
  const [updateState, setUpdateState] = useRequestState<OrderResponse>();
  const [listState, setListState] = useRequestState<OrderResponse[]>();
  const [snapshotLabel, setSnapshotLabel] = useState("No order loaded yet.");
  const [actionLog, setActionLog] = useState<ActionEntry[]>([]);
  const [filterOrderId, setFilterOrderId] = useState("");
  const [filterActionType, setFilterActionType] = useState("all");
  const [listOrderId, setListOrderId] = useState("");
  const [listCustomerId, setListCustomerId] = useState("");
  const [listStatus, setListStatus] = useState("");
  const [listUpdatedFrom, setListUpdatedFrom] = useState("");
  const [listUpdatedTo, setListUpdatedTo] = useState("");
  const [listPage, setListPage] = useState(0);
  const [listSize, setListSize] = useState(25);
  const [listSortBy, setListSortBy] = useState("updatedAt");
  const [listSortDir, setListSortDir] = useState("desc");

  const activeOrder = useMemo(() => {
    return updateState.data || trackState.data || registerState.data;
  }, [registerState.data, trackState.data, updateState.data]);

  const filteredActions = useMemo(() => {
    const orderFilter = filterOrderId.trim().toLowerCase();
    const typeFilter = filterActionType.toLowerCase();
    return actionLog.filter((entry) => {
      const matchesOrder = !orderFilter || entry.orderId.toLowerCase().includes(orderFilter);
      const matchesType = typeFilter === "all" || entry.label.toLowerCase().includes(typeFilter);
      return matchesOrder && matchesType;
    });
  }, [actionLog, filterActionType, filterOrderId]);

  const exportCsv = () => {
    if (!actionLog.length) return;
    const header = ["timestamp", "action", "orderId", "status"];
    const rows = actionLog.map((entry) => [
      entry.timestamp,
      entry.label,
      entry.orderId,
      entry.status ?? ""
    ]);
    const csv = [header, ...rows]
      .map((row) => row.map((value) => `\"${String(value).replace(/\"/g, '\"\"')}\"`).join(","))
      .join("\n");

    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "order-actions.csv";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const exportOrdersCsv = () => {
    if (!listState.data || !listState.data.length) return;
    const header = ["id", "customerId", "status", "createdAt", "updatedAt"];
    const rows = listState.data.map((order) => [
      order.id,
      order.customerId,
      order.status,
      order.createdAt,
      order.updatedAt
    ]);
    const csv = [header, ...rows]
      .map((row) => row.map((value) => `\"${String(value).replace(/\"/g, '\"\"')}\"`).join(","))
      .join("\n");

    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "orders.csv";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const exportOrdersJson = () => {
    if (!listState.data || !listState.data.length) return;
    const blob = new Blob([JSON.stringify(listState.data, null, 2)], {
      type: "application/json;charset=utf-8;"
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "orders.json";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  const onRegister = async () => {
    if (!registerOrderId || !registerCustomerId) {
      setRegisterState({ loading: false, error: "Order ID and Customer ID are required.", data: null });
      return;
    }
    setRegisterState({ loading: true, error: null, data: null });
    try {
      const response = await registerOrder({ orderId: registerOrderId, customerId: registerCustomerId });
      setRegisterState({ loading: false, error: null, data: response });
      setSnapshotLabel("Order created");
      setActionLog((prev) => [
        {
          label: "Created order",
          orderId: response.id,
          timestamp: new Date().toISOString(),
          status: response.status
        },
        ...prev
      ]);
    } catch (error) {
      setRegisterState({ loading: false, error: (error as Error).message, data: null });
    }
  };

  const onTrack = async () => {
    if (!trackOrderId) {
      setTrackState({ loading: false, error: "Order ID is required.", data: null });
      return;
    }
    setTrackState({ loading: true, error: null, data: null });
    try {
      const response = await getOrderById(trackOrderId);
      setTrackState({ loading: false, error: null, data: response });
      setSnapshotLabel("Order tracked");
      setActionLog((prev) => [
        {
          label: "Tracked order",
          orderId: response.id,
          timestamp: new Date().toISOString(),
          status: response.status
        },
        ...prev
      ]);
    } catch (error) {
      setTrackState({ loading: false, error: (error as Error).message, data: null });
    }
  };

  const onUpdate = async () => {
    if (!updateOrderId || !updateStatus) {
      setUpdateState({ loading: false, error: "Order ID and status are required.", data: null });
      return;
    }
    setUpdateState({ loading: true, error: null, data: null });
    try {
      const response = await updateOrderStatus(updateOrderId, {
        status: updateStatus,
        note: updateNote || null
      });
      setUpdateState({ loading: false, error: null, data: response });
      setSnapshotLabel("Order updated");
      setActionLog((prev) => [
        {
          label: "Updated order",
          orderId: response.id,
          timestamp: new Date().toISOString(),
          status: response.status
        },
        ...prev
      ]);
    } catch (error) {
      setUpdateState({ loading: false, error: (error as Error).message, data: null });
    }
  };

  const onListOrders = async () => {
    setListState({ loading: true, error: null, data: null });
    try {
      const updatedFrom = listUpdatedFrom ? new Date(listUpdatedFrom).toISOString() : undefined;
      const updatedTo = listUpdatedTo ? new Date(listUpdatedTo).toISOString() : undefined;
      const response = await listOrders({
        orderId: listOrderId || undefined,
        customerId: listCustomerId || undefined,
        status: listStatus || undefined,
        updatedFrom,
        updatedTo,
        page: listPage,
        size: listSize,
        sortBy: listSortBy,
        sortDir: listSortDir
      });
      setListState({ loading: false, error: null, data: response });
    } catch (error) {
      setListState({ loading: false, error: (error as Error).message, data: null });
    }
  };

  return (
    <div className="page">
      <header className="hero">
        <div>
          <p className="eyebrow">Order Tracking Console</p>
          <h1>Track. Validate. Move orders forward.</h1>
          <p className="lead">
            A focused operator console for the Order Tracking Spring Boot API. Register new orders, audit
            their lifecycle, and safely move statuses in line with the domain rules.
          </p>
        </div>
        <div className="hero-card">
          <h3>Live API</h3>
          <ul className="mini-list">
            <li>POST /api/orders</li>
            <li>GET /api/orders/{"{id}"}</li>
            <li>PUT /api/orders/{"{id}"}/status</li>
          </ul>
          <p className="muted">Authentication: HTTP Basic</p>
        </div>
      </header>

      <main className="grid">
        <section className="panel">
          <header>
            <h2>Register Order</h2>
            <p>Create a new order for a customer.</p>
          </header>
          <div className="field">
            <label>Order ID</label>
            <input
              value={registerOrderId}
              onChange={(event) => setRegisterOrderId(event.target.value)}
              placeholder="o-1001"
            />
          </div>
          <div className="field">
            <label>Customer ID</label>
            <input
              value={registerCustomerId}
              onChange={(event) => setRegisterCustomerId(event.target.value)}
              placeholder="c-2001"
            />
          </div>
          <button onClick={onRegister} disabled={registerState.loading}>
            {registerState.loading ? "Creating..." : "Register"}
          </button>
          {registerState.error && <p className="error">{registerState.error}</p>}
        </section>

        <section className="panel">
          <header>
            <h2>Track Order</h2>
            <p>Fetch the latest state of an order.</p>
          </header>
          <div className="field">
            <label>Order ID</label>
            <input
              value={trackOrderId}
              onChange={(event) => setTrackOrderId(event.target.value)}
              placeholder="o-1001"
            />
          </div>
          <button onClick={onTrack} disabled={trackState.loading}>
            {trackState.loading ? "Loading..." : "Track"}
          </button>
          {trackState.error && <p className="error">{trackState.error}</p>}
        </section>

        <section className="panel">
          <header>
            <h2>Update Status</h2>
            <p>Move an order to the next valid state.</p>
          </header>
          <div className="field">
            <label>Order ID</label>
            <input
              value={updateOrderId}
              onChange={(event) => setUpdateOrderId(event.target.value)}
              placeholder="o-1001"
            />
          </div>
          <div className="field">
            <label>Status</label>
            <select value={updateStatus} onChange={(event) => setUpdateStatus(event.target.value as OrderStatus)}>
              {STATUS_OPTIONS.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>Note</label>
            <input
              value={updateNote}
              onChange={(event) => setUpdateNote(event.target.value)}
              placeholder="Packed and handed to courier"
            />
          </div>
          <button onClick={onUpdate} disabled={updateState.loading}>
            {updateState.loading ? "Updating..." : "Update"}
          </button>
          {updateState.error && <p className="error">{updateState.error}</p>}
        </section>

        <section className="panel panel-wide action-panel">
          <header>
            <h2>Last Action</h2>
            <p>Most recent activity in this session.</p>
          </header>
          <div className="action-controls">
            <div className="field">
              <label>Filter by Order ID</label>
              <input
                value={filterOrderId}
                onChange={(event) => setFilterOrderId(event.target.value)}
                placeholder="o-1001"
              />
            </div>
            <div className="field">
              <label>Action Type</label>
              <select value={filterActionType} onChange={(event) => setFilterActionType(event.target.value)}>
                <option value="all">All</option>
                <option value="created">Created</option>
                <option value="tracked">Tracked</option>
                <option value="updated">Updated</option>
              </select>
            </div>
            <button onClick={exportCsv} disabled={!actionLog.length}>
              Export CSV
            </button>
          </div>
          {actionLog.length ? (
            <div className="action-banner">
              <div>
                <span className="label">Action</span>
                <strong>{actionLog[0].label}</strong>
              </div>
              <div>
                <span className="label">Order</span>
                <strong className="mono">{actionLog[0].orderId}</strong>
              </div>
              <div>
                <span className="label">Status</span>
                <strong className={`status status-${(actionLog[0].status || "").toLowerCase()}`}>
                  {actionLog[0].status || "-"}
                </strong>
              </div>
              <div>
                <span className="label">Time</span>
                <strong>{formatDate(actionLog[0].timestamp)}</strong>
              </div>
            </div>
          ) : (
            <p className="muted">No actions yet.</p>
          )}
          {filteredActions.length > 1 && (
            <div className="action-log">
              <div className="label">Recent actions</div>
              <ul>
                {filteredActions.slice(1, 8).map((entry) => (
                  <li key={`${entry.timestamp}-${entry.orderId}`}>
                    <span>{entry.label}</span>
                    <span className="mono">{entry.orderId}</span>
                    <span>{formatDate(entry.timestamp)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>

        <section className="panel panel-wide">
          <header>
            <h2>All Orders</h2>
            <p>Filter and export orders from the backend list endpoint.</p>
          </header>
          <div className="list-controls">
            <div className="field">
              <label>Order ID</label>
              <input
                value={listOrderId}
                onChange={(event) => setListOrderId(event.target.value)}
                placeholder="o-1001"
              />
            </div>
            <div className="field">
              <label>Customer ID</label>
              <input
                value={listCustomerId}
                onChange={(event) => setListCustomerId(event.target.value)}
                placeholder="c-2001"
              />
            </div>
            <div className="field">
              <label>Status</label>
              <select value={listStatus} onChange={(event) => setListStatus(event.target.value)}>
                <option value="">All</option>
                {STATUS_OPTIONS.map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Updated From</label>
              <input
                type="datetime-local"
                value={listUpdatedFrom}
                onChange={(event) => setListUpdatedFrom(event.target.value)}
              />
            </div>
            <div className="field">
              <label>Updated To</label>
              <input
                type="datetime-local"
                value={listUpdatedTo}
                onChange={(event) => setListUpdatedTo(event.target.value)}
              />
            </div>
            <div className="field">
              <label>Page</label>
              <input
                type="number"
                min={0}
                value={listPage}
                onChange={(event) => setListPage(Number(event.target.value))}
              />
            </div>
            <div className="field">
              <label>Size</label>
              <input
                type="number"
                min={1}
                max={500}
                value={listSize}
                onChange={(event) => setListSize(Number(event.target.value))}
              />
            </div>
            <div className="field">
              <label>Sort By</label>
              <select value={listSortBy} onChange={(event) => setListSortBy(event.target.value)}>
                <option value="updatedAt">Updated</option>
                <option value="createdAt">Created</option>
              </select>
            </div>
            <div className="field">
              <label>Sort Direction</label>
              <select value={listSortDir} onChange={(event) => setListSortDir(event.target.value)}>
                <option value="desc">Newest first</option>
                <option value="asc">Oldest first</option>
              </select>
            </div>
            <button onClick={onListOrders} disabled={listState.loading}>
              {listState.loading ? "Loading..." : "Fetch Orders"}
            </button>
            <button onClick={exportOrdersCsv} disabled={!listState.data?.length}>
              Export CSV
            </button>
            <button onClick={exportOrdersJson} disabled={!listState.data?.length}>
              Export JSON
            </button>
          </div>
          {listState.error && <p className="error">{listState.error}</p>}
          {listState.data?.length ? (
            <div className="orders-table">
              <div className="orders-header">
                <span>Order</span>
                <span>Customer</span>
                <span>Status</span>
                <span>Updated</span>
              </div>
              {listState.data.map((order) => (
                <div key={order.id} className="orders-row">
                  <span className="mono">{order.id}</span>
                  <span className="mono">{order.customerId}</span>
                  <span className={`status-chip status-${String(order.status).toLowerCase()}`}>
                    {order.status}
                  </span>
                  <span>{formatDate(order.updatedAt)}</span>
                </div>
              ))}
            </div>
          ) : (
            <p className="muted">No orders loaded yet.</p>
          )}
        </section>

        <section className="panel panel-wide">
          <header>
            <h2>Order Snapshot</h2>
            <p>{snapshotLabel}</p>
          </header>
          {activeOrder ? (
            <div className="order-grid">
              <div>
                <span className="label">Order</span>
                <strong className="value mono">{activeOrder.id}</strong>
              </div>
              <div>
                <span className="label">Customer</span>
                <strong className="value mono">{activeOrder.customerId}</strong>
              </div>
              <div>
                <span className="label">Status</span>
                <strong className={`status status-${activeOrder.status.toLowerCase()}`}>
                  {activeOrder.status}
                </strong>
              </div>
              <div>
                <span className="label">Created</span>
                <strong className="value">{formatDate(activeOrder.createdAt)}</strong>
              </div>
              <div>
                <span className="label">Updated</span>
                <strong className="value">{formatDate(activeOrder.updatedAt)}</strong>
              </div>
              <div className="history">
                <span className="label">History</span>
                <ul>
                  {activeOrder.history?.length ? (
                    activeOrder.history.map((event, index) => (
                      <li key={`${event.status}-${index}`}>
                        <span className={`status-chip status-${event.status.toLowerCase()}`}>
                          {event.status}
                        </span>
                        <span>{formatDate(event.occurredAt)}</span>
                        <span className="note">{event.note || "-"}</span>
                      </li>
                    ))
                  ) : (
                    <li>No tracking events yet.</li>
                  )}
                </ul>
              </div>
            </div>
          ) : (
            <p className="muted">No order loaded yet.</p>
          )}
        </section>
      </main>
    </div>
  );
}
