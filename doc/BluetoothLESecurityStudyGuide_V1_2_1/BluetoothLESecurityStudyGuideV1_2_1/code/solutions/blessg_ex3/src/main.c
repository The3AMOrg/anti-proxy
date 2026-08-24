#include <zephyr/types.h>
#include <stddef.h>
#include <string.h>
#include <errno.h>
#include <zephyr.h>
#include <device.h>

#include <bluetooth/bluetooth.h>
#include <bluetooth/hci.h>
#include <bluetooth/conn.h>
#include <bluetooth/uuid.h>
#include <bluetooth/gatt.h>
#include <settings/settings.h>

#include <services/custom_service.h>

#define DEVICE_NAME CONFIG_BT_DEVICE_NAME
#define DEVICE_NAME_LEN (sizeof(DEVICE_NAME) - 1)

struct bt_conn *default_conn;

static const struct bt_data ad[] = {
		BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
		BT_DATA(BT_DATA_NAME_COMPLETE, DEVICE_NAME, DEVICE_NAME_LEN),
};

// TODO implement auth_cancel
static void pairing_cancel(struct bt_conn *conn)
{
	char addr[BT_ADDR_LE_STR_LEN];
	bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));
	printk("Pairing cancelled by device: %s\n", addr);
}

// TODO specify callbacks for pairing - IO Capabilities are implied from this struct
static struct bt_conn_auth_cb pairing_cb_display = {
		.passkey_display = NULL,
		.passkey_entry = NULL,
		.cancel = pairing_cancel,
};

static void connected(struct bt_conn *conn, uint8_t err)
{
	if (err)
	{
		printk("Connection failed (err %u)\n", err);
	}
	else
	{
		printk("Connected\n");
		default_conn = bt_conn_ref(conn);

        // TO DO request mode 1 level 2 security
		// L2 security means Just Works pairing will be used - encryption with no MITM protection
		int rc = bt_conn_set_security(default_conn, BT_SECURITY_L2);
	}
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	printk("Disconnected (reason %u)\n", reason);

	if (default_conn)
	{
		bt_conn_unref(default_conn);
		default_conn = NULL;
	}
}

// TODO implement security_level_changed
static void security_level_changed(struct bt_conn *conn, bt_security_t level, enum bt_security_err err)
{
	printk("security_level_changed to %d\n", level);
}

// TODO specify callback for security level changing
static struct bt_conn_cb conn_callbacks = {
		.connected = connected,
		.disconnected = disconnected,
		.security_changed = security_level_changed,
};

static void bt_ready(int err)
{
	if (err)
	{
		printk("Bluetooth init failed (err %d)\n", err);
		return;
	}

	printk("About to initialise custom service\n");
	custom_service_init();
	printk("done initialise custom service\n");

	if (IS_ENABLED(CONFIG_SETTINGS))
	{
		printk("About to load settings\n");
		settings_load();
		printk("done loading settings\n");
	}

	printk("About to start advertising\n");
	err = bt_le_adv_start(BT_LE_ADV_CONN, ad, ARRAY_SIZE(ad), NULL, 0);
	printk("done started advertising\n");
	if (err)
	{
		printk("Advertising failed to start (err %d)\n", err);
		return;
	}

	printk("Advertising successfully started\n");
}

void clear_all_bonds()
{

	/** Clear pairing information.
  *
  * @param id    Local identity (mostly just BT_ID_DEFAULT).
  * @param addr  Remote address, NULL or BT_ADDR_LE_ANY to clear all remote
  *              devices.
  *
  * @return 0 on success or negative error value on failure.
  */
	printk("clearing all bonds\n");
	int rc = bt_unpair(BT_ID_DEFAULT, BT_ADDR_LE_ANY);
	printk("done [%d]\n", rc);
}

void main(void)
{

	printk("starting JW\n");

	int err;

	err = bt_enable(bt_ready);
	if (err)
	{
		return;
	}

	clear_all_bonds();

	bt_conn_cb_register(&conn_callbacks);

    // TODO register callback for authentication step
	bt_conn_auth_cb_register(&pairing_cb_display);
}
