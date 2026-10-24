#!/usr/bin/env python3
"""Generate an experimental standalone project against captured Tasker XML schemas."""
from pathlib import Path
import json
import xml.etree.ElementTree as E

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'MockGPS Beta - '

def text(parent, tag, value, **attrs):
    element = E.SubElement(parent, tag, attrs)
    element.text = str(value)
    return element

def condition(parent, lhs, rhs, op=0):
    conditions = E.SubElement(parent, 'ConditionList', sr='if')
    c = E.SubElement(conditions, 'Condition', sr='c0', ve='3')
    for k, v in [('lhs', lhs), ('op', op), ('rhs', rhs)]:
        text(c, k, v)

root = E.Element('TaskerData', sr='', dvi='1', tv='6.3.13')
tasks = []

def task(number, name, collision=0):
    node = E.Element('Task', sr=f'task{number}')
    for tag, value in [('cdate', 1792796400000), ('edate', 1792796400000), ('id', number),
                       ('nme', PREFIX + name), ('pri', 10), ('rty', collision)]:
        text(node, tag, value)
    tasks.append(node)
    return node

def action(t, code, arguments=(), label=None, continue_error=False):
    a = E.SubElement(t, 'Action', sr=f'act{len(t.findall("Action"))}', ve='7')
    text(a, 'code', code)
    if label:
        text(a, 'label', label)
    if continue_error:
        text(a, 'se', 'false')  # Captured Java export's continue-after-error encoding.
    for i, value in enumerate(arguments):
        if isinstance(value, int):
            E.SubElement(a, 'Int', sr=f'arg{i}', val=str(value))
        else:
            text(a, 'Str', value, sr=f'arg{i}', ve='3')
    return a

def js(t, code):
    action(t, 129, [code, '', 1, 10])

def java(t, returned, owner, signature, *params):
    action(t, 664, [returned, owner, signature] + list(params) + [''] * (7-len(params)))

def send(t):
    js(t, (ROOT/'tasker/dispatch.js').read_text())
    stop = action(t, 137, [1, ''], 'Unconfigured/invalid counter: stop without sending; never reset it')
    condition(stop, '%mgps_ready', '1', op=1)
    java(t, 'mockgps_intent', 'Intent', 'parseUri\n{Intent} (String, int)', '%mgps_uri', '1')
    java(t, '', 'CONTEXT', 'sendBroadcast\n{} (Intent)', 'mockgps_intent')

# One mutation dispatcher. Abort-existing ensures the newest observation supersedes
# an interrupted old sender; a completed action finishes before cancellation.
dispatch = task(9101, 'Dispatch', 1)
js(dispatch, "setLocal('mgps_device', global('MockGPSAddress') || global('MockGPSVehicle') || 'Sportage Hybrid');")
action(dispatch, 549, ['%mgps_bt', 0, 0, 0])
a = action(dispatch, 37); condition(a, '%BLUE', 'on')
action(dispatch, 341, [7, '%mgps_device', '%mgps_bt'], continue_error=True)
action(dispatch, 38)
send(dispatch)

status = task(9102, 'Status')
js(status, "setLocal('par1', 'status');")
send(status)  # No mutation counter increment, never cancels Dispatch.
for number, name, command in [(9103, 'Stop', 'stop'), (9104, 'Unknown test', 'vehicle_unknown'),
                               (9105, 'Reconcile', ''), (9106, 'Recovery', 'vehicle_unknown')]:
    node = task(number, name, 1)
    action(node, 130, [PREFIX+'Dispatch', 10, command, '', '', 0, 0, '', 0, 0, 0])
help_task = task(9107, 'Setup Help')
action(help_task, 548, ['EXPERIMENTAL: read tasker/README.md. No token or counter is initialized. Keep old tasks disabled before adopting this sender.', 1, 0, '', '', '', '', '', '', 1, '', 1, 0, '', 0, ''])

profiles = []
def profile(number, name, code, kind='Event', args=(), exit_task=None, target=9101):
    p = E.Element('Profile', sr=f'prof{number}', ve='2')
    for tag, value in [('cdate',1792796400000), ('edate',1792796400000), ('id', number),
                       ('mid0',target), ('nme',PREFIX+name)]: text(p,tag,value)
    if exit_task: text(p,'mid1',exit_task)
    ctx = E.SubElement(p, kind, sr='con0', ve='2');text(ctx,'code',code)
    for i, value in enumerate(args):
        if isinstance(value,int): E.SubElement(ctx,'Int',sr=f'arg{i}',val=str(value))
        else: text(ctx,'Str',value,sr=f'arg{i}',ve='3')
    gate = E.SubElement(p,'State',sr='con1',ve='2');text(gate,'code',165)
    condition(gate,'%MockGPSReady','1')
    profiles.append(p)
profile(9201,'Sportage Hybrid',3,'State',['Sportage Hybrid',''],exit_task=9101)
profile(9202,'Bluetooth off',2,'State',[0])
profile(9203,'Monitor start',307,target=9106)
profile(9204,'Device boot',411,target=9106)
for p in profiles: root.append(p)
project = E.SubElement(root,'Project',sr='proj0',ve='2')
for tag, value in [('cdate',1792796400000),('name','MockGPS Beta EXPERIMENTAL'),
                   ('pids',','.join(p.findtext('id') for p in profiles)),
                   ('tids',','.join(t.findtext('id') for t in tasks))]:text(project,tag,value)
for t in tasks:root.append(t)
E.indent(root,space='\t')
content = E.tostring(root,encoding='unicode',xml_declaration=False)
output = ROOT/'tasker/MockGPS.prj.xml'
output.write_text('<?xml version="1.0" encoding="utf-8"?>\n'+content+'\n')
# Schema comparison uses captured facts, not guesses about action argument order.
defs = {d['code']:d for d in json.loads((ROOT/'tasker/fixtures/schema-facts.json').read_text())['definitions'] if d['kind']=='action'}
fixture = E.parse(ROOT/'tasker/fixtures/java-function-export.xml').getroot().find('.//Action')
java_shape = [(x.tag,x.get('sr')) for x in fixture if x.tag=='Str']
for a in root.iter('Action'):
    code=int(a.findtext('code'))
    if code==664:
        assert [(x.tag,x.get('sr')) for x in a if x.tag=='Str']==java_shape
    if code in defs:
        args=[x for x in a if x.tag in ('Str','Int')]
        assert len(args)==len(defs[code]['args']),code
        for x,d in zip(args,defs[code]['args']):
            assert x.get('sr')=='arg'+d['num']
            assert x.tag==('Str' if d['dataType']=='str' else 'Int')
print('Generated project; Java/action schemas match captured fixture and definition facts. Actual Tasker import NOT RUN.')
