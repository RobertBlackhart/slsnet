# Copyright (c) 2017 by Telcoware
# All Rights Reserved.
# SONA Monitoring Solutions.

import sys
import alarm_event
import chk_onos
import cmd_proc

from datetime import datetime
from subprocess import Popen
from subprocess import PIPE
from api.config import CONF
from api.sona_log import LOG
from api.watcherdb import DB


def periodic(conn, pre_stat, db_log):
    try:
        cur_info = {}
        #LOG.info('Periodic checking %s', str(CONF.watchdog()['check_system']))

        try:
            node_list = cmd_proc.get_node_list('all', 'nodename, ip_addr, username, type, sub_type')
            if not node_list:
                LOG.info("Not Exist Node data ...")
                return
        except:
            LOG.exception()
            return

        # Read cur alarm status
        sql = 'SELECT nodename, item, grade FROM ' + DB.EVENT_TBL

        db_log.write_log(sql)
        cur_grade = conn.cursor().execute(sql).fetchall()

        for nodename, item, grade in cur_grade:
            if not cur_info.has_key(nodename):
                cur_info[nodename] = {}
            cur_info[nodename][item] = grade

        for node_name, node_ip, user_name, type, sub_type in node_list:
            #LOG.info('------------------------------------ ' + node_name + ' START ------------------------------------')

            onos_cluster = 'fail'
            onos_device = 'fail'
            onos_link = 'fail'
            onos_app = 'fail'
            onos_rest = 'fail'

            # ping check
            ping = net_check(node_ip)
            ping_reason = []
            if ping == 'nok':
                reason.append('ping transmit faild')
            ping = alarm_event.process_event(conn, db_log, node_name, type, 'PING', cur_info[node_name]['PING'], ping, ping_reason)

            if ping == 'ok':
                if type.upper() == 'ONOS':
                    # check connection
                    onos_cluster, onos_device, onos_link, cluster_reason, device_reason, link_reason = chk_onos.onos_conn_check(conn, db_log, node_name, node_ip)
                    onos_cluster = alarm_event.process_event(conn, db_log, node_name, type, 'ONOS_CLUSTER',
                                                             cur_info[node_name]['ONOS_CLUSTER'], onos_cluster, cluster_reason)
                    onos_device = alarm_event.process_event(conn, db_log, node_name, type, 'ONOS_DEVICE',
                                                             cur_info[node_name]['ONOS_DEVICE'], onos_device, device_reason)
                    onos_link = alarm_event.process_event(conn, db_log, node_name, type, 'ONOS_LINK',
                                                             cur_info[node_name]['ONOS_LINK'], onos_link, link_reason)
                    LOG.info('[' + node_name + '][ONOS_CLUSTER][' + onos_cluster + ']' + str(cluster_reason))
                    LOG.info('[' + node_name + '][ONOS_DEVICE][' + onos_device + ']' + str(device_reason))
                    LOG.info('[' + node_name + '][ONOS_LINK][' + onos_link + ']' + str(link_reason))

                    # check app
                    onos_app, reason = chk_onos.onos_app_check(conn, db_log, node_name, node_ip)
                    onos_app = alarm_event.process_event(conn, db_log, node_name, type, 'ONOS_APP',
                                                         cur_info[node_name]['ONOS_APP'], onos_app, reason)
                    #LOG.info('[' + node_name + '][ONOS_APP][' + onos_app + ']' + str(reason))

                    # check web
                    onos_rest, reason = chk_onos.onos_rest_check(conn, db_log, node_name, node_ip)
                    onos_rest = alarm_event.process_event(conn, db_log, node_name, type, 'ONOS_REST',
                                                         cur_info[node_name]['ONOS_REST'], onos_rest, reason)
                    #LOG.info('[' + node_name + '][ONOS_REST][' + onos_rest + ']' + str(reason))

            try:
                sql = 'UPDATE ' + DB.STATUS_TBL + \
                      ' SET' + \
                      ' PING = \'' + ping + '\',' + \
                      ' ONOS_CLUSTER = \'' + onos_cluster + '\',' + \
                      ' ONOS_DEVICE = \'' + onos_device + '\',' + \
                      ' ONOS_LINK = \'' + onos_link + '\',' + \
                      ' ONOS_APP = \'' + onos_app + '\',' + \
                      ' ONOS_REST = \'' + onos_rest + '\',' + \
                      ' time = \'' + str(datetime.now()) + '\'' + \
                      ' WHERE nodename = \'' + node_name + '\''
                db_log.write_log('----- UPDATE TOTAL SYSTEM INFO -----\n' + sql)

                if DB.sql_execute(sql, conn) != 'SUCCESS':
                    db_log.write_log('[FAIL] TOTAL SYSTEM INFO DB Update Fail.')
            except:
                LOG.exception()

            LOG.info('chk_onos[%s]: ping=%s cluster=%s device=%s link=%s app=%s rest=%s' %
                     (node_name, ping, onos_cluster, onos_device, onos_link, onos_app, onos_rest))

    except:
        LOG.exception()

    return pre_stat

def net_check(node):
    try:
        if CONF.watchdog()['method'] == 'ping':
            timeout = CONF.watchdog()['timeout']
            if sys.platform == 'darwin':
                timeout = timeout * 1000

            cmd = 'ping -c1 -W%d -n %s' % (timeout, node)

            result = Popen(cmd, stdout=PIPE, stderr=PIPE, shell=True)
            output, error = result.communicate()

            if result.returncode != 0:
                LOG.error("\'%s\' Network Check Error(%d) ", node, result.returncode)
                return 'nok'
            else:
                return 'ok'
    except:
        LOG.exception()